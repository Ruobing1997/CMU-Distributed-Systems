/* Skeleton code for Server */
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Server implements ProjectLib.CommitServing {
	static ProjectLib PL;
	static MessageHandlingCallBack messageHandlingCallBack;
	static Map<String, FileInfo> fileName2InfoMap = new ConcurrentHashMap<>();
	static Map<String, String> fileName2DecisionMap = new ConcurrentHashMap<>();
	static boolean isRecoveryCompleted = false;
	public void startCommit( String filename, byte[] img, String[] sources ) {
		TwoPCLogic twoPCLogic = new TwoPCLogic(filename, img, sources);
		twoPCLogic.setPL(PL);
		twoPCLogic.setMessageHandlingCallBack(messageHandlingCallBack);
		twoPCLogic.startCommit();
	}

	public static void recoverFromFailure() {
		String targetPath = System.getProperty("user.dir") + File.separator;
		Path logFilePath = Paths.get(targetPath, Constants.SEVER_LOG_FILE_NAME);
		// check if file exists
		File file = new File(logFilePath.toString());
		if (file.exists()) {
			// read file line by line:
			try (BufferedReader bufferedReader = new BufferedReader(new FileReader(file))) {
				String currentLine;
				while ((currentLine = bufferedReader.readLine()) != null) {
					if (currentLine.isEmpty()) continue;
					String[] splittedLine = currentLine.split(" ");
					if (splittedLine.length < 2) continue;
					String phase = splittedLine[1];
					// the phase should be either PREPARE or DECISION or DONE
					if (phase.equals(Constants.LOG_PHASE_1)) {
						ArrayList<String> sourceList =
								new ArrayList<>(
										Arrays.asList(splittedLine)
												.subList(2, splittedLine.length)
								);
						String[] source = sourceList.toArray(new String[0]);
						fileName2InfoMap.put(
								splittedLine[0],
								new FileInfo(splittedLine[0], null, source)
						);
					} else if (phase.equals(Constants.LOG_PHASE_2)) {
						fileName2DecisionMap.put(splittedLine[0], splittedLine[2]);
					} else if (phase.equals(Constants.LOG_PHASE_3)) {
						fileName2InfoMap.remove(splittedLine[0]);
						fileName2DecisionMap.remove(splittedLine[0]);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			System.err.println("No log file found");
		}
	}
	
	public static void main (String args[]) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");

		// check if need recovery at the very beginning:
		recoverFromFailure();

		Server srv = new Server();
		messageHandlingCallBack = new MessageHandlingCallBack();
		PL = new ProjectLib(Integer.parseInt(args[0]), srv, messageHandlingCallBack);

		// recover from failure:
		for (Map.Entry<String, FileInfo> entry : fileName2InfoMap.entrySet()) {
			String filename = entry.getKey();
			FileInfo fileInfo = entry.getValue();
			if (fileName2DecisionMap.containsKey(filename)) {
				String decision = fileName2DecisionMap.get(filename);
				TwoPCLogic twoPCLogic = new TwoPCLogic(fileInfo.fname, fileInfo.img, fileInfo.sources);
				twoPCLogic.setPL(PL);
				twoPCLogic.setMessageHandlingCallBack(messageHandlingCallBack);
				twoPCLogic.setCommitStatus(decision);
				twoPCLogic.startCommit();
			} else {
				TwoPCLogic twoPCLogic = new TwoPCLogic(fileInfo.fname, fileInfo.img, fileInfo.sources);
				twoPCLogic.setPL(PL);
				twoPCLogic.setMessageHandlingCallBack(messageHandlingCallBack);
				twoPCLogic.setCommitStatus(Constants.DECISION_ABORT);
				twoPCLogic.startCommit();
			}
		}

		isRecoveryCompleted = true;

		// main loop
		while (true) {
			ProjectLib.Message msg = PL.getMessage();
			System.out.println( "Server: Got message from " + msg.addr );
		}
	}

	public static class TwoPCLogic implements Runnable{
		private String fname;
		private byte[] img;
		private String[] sources;
		private String commitStatus = Constants.INITIAL_COMMIT;
		private Thread thread;
		private ProjectLib PL;
		private MessageHandlingCallBack messageHandlingCallBack;
		ConcurrentHashMap<String, List<String>> sourceNode2FileName = new ConcurrentHashMap<>();
		TwoPCLogic(String fname, byte[] img, String[] sources) {
			this.fname = fname;
			this.img = img;
			this.sources = sources;
		}

		public void setPL(ProjectLib PL) {
			this.PL = PL;
		}

		public void setMessageHandlingCallBack(MessageHandlingCallBack messageHandlingCallBack) {
			this.messageHandlingCallBack = messageHandlingCallBack;
		}

		public void setCommitStatus(String commitStatus) {
			this.commitStatus = commitStatus;
		}


		/**
		 * When an object implementing interface <code>Runnable</code> is used
		 * to create a thread, starting the thread causes the object's
		 * <code>run</code> method to be called in that separately executing
		 * thread.
		 * <p>
		 * The general contract of the method <code>run</code> is that it may
		 * take any action whatsoever.
		 *
		 * @see Thread#run()
		 */
		@Override
		public void run() {
			for (String source : sources) {
				String[] temp = source.split(Constants.ADDR_FILE_DELIMETER);
				String sourceNode = temp[0];
				String fileName = temp[1];
				sourceNode2FileName.computeIfAbsent(sourceNode, key -> new ArrayList<>()).add(fileName);
			}
			System.err.println("sourceNode2FileName: " + sourceNode2FileName);
			System.err.println("commitStatus: " + commitStatus);
			if (this.commitStatus.equals(Constants.INITIAL_COMMIT)) {
				while (!isRecoveryCompleted) {
					try {
						Thread.sleep(Constants.THREAD_SLEEP_TIME);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				System.err.println("$$Start 2PC Phase Prepare$$");
				startPhasePrepare();
			} else if (this.commitStatus.equals(Constants.DECISION_ABORT)) {
				logDecision();
			}

			System.err.println("$$Start 2PC Phase Commit$$");
			startPhaseCommit();
			System.err.println("$$End 2PC and log Conclusion$$");
			logConclusion();
		}

		private void logConclusion() {
			System.err.println("$$Log Conclusion in Server$$");
			String targetPath = System.getProperty("user.dir") + File.separator;
			Path logFilePath = Paths.get(targetPath, Constants.SEVER_LOG_FILE_NAME);
			String logEntry = fname + " " + Constants.LOG_PHASE_3 + "\n";
			try (BufferedWriter logWriter = Files.newBufferedWriter(logFilePath,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				PL.fsync();
				System.err.println("Logged Conclusions");
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}

		private void startPhasePrepare() {
			// Ask for votes:
			askAllNodesForVotes();
			// Get Reply
			getDecision();
			// log decision:
			logDecision();
		}

		private void startPhaseCommit() {
			// send decision until ACK
			sendDecisionUntilAck();
		}

		private void sendDecisionUntilAck() {
			System.err.println("$$Send decision until ACK$$");
			messageHandlingCallBack.initializeMessageQueue(fname);
			for (Map.Entry<String, List<String>> entry : sourceNode2FileName.entrySet()) {
				String sourceNode = entry.getKey();
				List<String> fileNames = entry.getValue();
				String[] sources = fileNames.toArray(new String[0]);
				AdvancedMessage advancedMessage = new AdvancedMessage(fname, sources, null, commitStatus);
				try {
					byte[] message = AdvancedMessageSerializer.serialize(advancedMessage);
					ProjectLib.Message msg = new ProjectLib.Message(sourceNode, message);
					PL.sendMessage(msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			System.err.println("$$Waiting ACK back$$" + sourceNode2FileName);

			while (!sourceNode2FileName.isEmpty()) {
				System.err.println("$$While in Empty$$ " + sourceNode2FileName);
				long startTime = System.currentTimeMillis();
				while (System.currentTimeMillis() - startTime < Constants.TIMEOUT) {
					if (!messageHandlingCallBack.getMessageQueue(fname).isEmpty()) {
						ProjectLib.Message msg = messageHandlingCallBack.getMessageQueue(fname).poll();
						try {
							assert msg != null;
							AdvancedMessage receivedMessage = AdvancedMessageSerializer.deserialize(msg.body);
							System.err.println("$$Received Message$$: " + receivedMessage.toString());
							if (receivedMessage.getMode().equals(Constants.ACK)) {
								System.err.println("$$Received ACK from " + msg.addr + " for " + fname);
								sourceNode2FileName.remove(msg.addr);
							}
						} catch (IOException | ClassNotFoundException e) {
							e.printStackTrace();
						}
					}
				}

				for (Map.Entry<String, List<String>> entry : sourceNode2FileName.entrySet()) {
					String sourceNode = entry.getKey();
					List<String> fileNames = entry.getValue();
					String[] sources = fileNames.toArray(new String[0]);
					AdvancedMessage advancedMessage = new AdvancedMessage(fname, sources, null, commitStatus);
					try {
						byte[] message = AdvancedMessageSerializer.serialize(advancedMessage);
						ProjectLib.Message msg = new ProjectLib.Message(sourceNode, message);
						PL.sendMessage(msg);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			messageHandlingCallBack.removeMessageQueue(fname);
		}

		private void askAllNodesForVotes() {
			System.err.println("$$Ask all nodes for votes$$");
			for (Map.Entry<String, List<String>> entry : sourceNode2FileName.entrySet()) {
				String sourceNode = entry.getKey();
				List<String> fileNames = entry.getValue();
				String[] sources = fileNames.toArray(new String[0]);
				AdvancedMessage advancedMessage = new AdvancedMessage(fname, sources, img, Constants.PREPARE);
				try {
					byte[] message = AdvancedMessageSerializer.serialize(advancedMessage);
					ProjectLib.Message msg = new ProjectLib.Message(sourceNode, message);
					PL.sendMessage(msg);
				} catch (IOException e) {
					e.printStackTrace();
				}

			}
		}

		private void getDecision() {
			System.err.println("$$Get Decision$$");
			// case1: at least one node says no or times out: abort
			long startTime = System.currentTimeMillis();
			int agreeUsers = 0;
			while (System.currentTimeMillis() - startTime < Constants.TIMEOUT && agreeUsers < sourceNode2FileName.size()) {
				if (!messageHandlingCallBack.getMessageQueue(fname).isEmpty()) {
					ProjectLib.Message msg = messageHandlingCallBack.getMessageQueue(fname).poll();
					try {
						assert msg != null;
						AdvancedMessage advancedMessage = AdvancedMessageSerializer.deserialize(msg.body);
						if (advancedMessage.getMode().equals(Constants.REFUSE)) {
							commitStatus = Constants.DECISION_ABORT;
							break;
						} else {
							agreeUsers++;
						}
					} catch (IOException | ClassNotFoundException e) {
						e.printStackTrace();
					}
				}
			}
			// case2: all nodes say yes: commit
			if (agreeUsers >= sourceNode2FileName.size()) {
				commitStatus = Constants.DECISION_COMMIT;
			}
			// commit side effects at server side:
			// publish collage written to the Server working directory:
			if (commitStatus.equals(Constants.DECISION_COMMIT)) {
				String targetPath = System.getProperty("user.dir") + File.separator + fname;
				try {
					FileOutputStream fos = new FileOutputStream(targetPath);
					fos.write(img);
					fos.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		private synchronized void logDecision() {
			System.err.println("$$Log Decision in Server$$");
			String targetPath = System.getProperty("user.dir") + File.separator;
			Path logFilePath = Paths.get(targetPath, Constants.SEVER_LOG_FILE_NAME);
			String logEntry = fname + " " + Constants.LOG_PHASE_2 + " " + commitStatus + "\n";

			try (BufferedWriter logWriter = Files.newBufferedWriter(logFilePath,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				PL.fsync();
				System.err.println("Logged Decision");
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}

		public void startCommit() {
			messageHandlingCallBack.initializeMessageQueue(fname);
			logListOfServers();
			PL.fsync();
			if (thread == null) {
				thread = new Thread(this);
				thread.start();
			}
		}

		private void logListOfServers() {
			System.err.println("$$Log List of servers in Server$$");
			String targetPath = System.getProperty("user.dir") + File.separator;
			Path logFilePath = Paths.get(targetPath, Constants.SEVER_LOG_FILE_NAME);
			String sourcesString = String.join(" ", Arrays.stream(sources).toArray(String[]::new));
			String logEntry = fname + " " + Constants.LOG_PHASE_1 + " " + sourcesString + "\n";

			try (BufferedWriter logWriter = Files.newBufferedWriter(logFilePath,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				PL.fsync();
				System.err.println("Logged List of servers");
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}
	}
}

