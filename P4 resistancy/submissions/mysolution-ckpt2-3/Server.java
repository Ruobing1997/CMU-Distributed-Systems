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

/**
 * The Server class includes the logic for running a server and a logic
 * of 2PC protocol. It implements the ProjectLib.CommitServing interface.
 * It also provides a failure recovery logic based on the log file.
 * The server will use the call back implementation for accepting messages.
 *
 * @author Ruobing Wang ruobing2
 */
public class Server implements ProjectLib.CommitServing {
	static ProjectLib PL;
	static MessageHandlingCallBack messageHandlingCallBack;
	static Map<String, FileInfo> fileName2InfoMap
			= new ConcurrentHashMap<>();
	static Map<String, String> fileName2DecisionMap
			= new ConcurrentHashMap<>();
	static boolean isRecoveryCompleted = false;

	/**
	 * The logic of server starts to commit a file.
	 *
	 * @param filename the name of the file.
	 * @param img the image of the file.
	 * @param sources the list of sources of the file.
	 */
	public void startCommit( String filename,
							 byte[] img,
							 String[] sources ) {
		TwoPCLogic twoPCLogic = new TwoPCLogic(filename, img, sources);
		twoPCLogic.setPL(PL);
		twoPCLogic.setMessageHandlingCallBack(messageHandlingCallBack);
		twoPCLogic.startCommit();
	}

	/**
	 * The recovery mechanism for the server.
	 * It will first read the log file and then
	 * divide the recovery into three different cases to
	 * abort or recover accordingly.
	 */
	public static void recoverFromFailure() {
		String targetPath = System.getProperty("user.dir") + File.separator;
		Path logFilePath = Paths.get(targetPath,
				Constants.SEVER_LOG_FILE_NAME);
		// check if file exists
		File file = new File(logFilePath.toString());
		if (file.exists()) {
			// read file line by line:
			try (BufferedReader bufferedReader =
						 new BufferedReader(new FileReader(file))) {
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
												.subList(2,
														splittedLine.length)
								);
						String[] source = sourceList.toArray(new String[0]);
						fileName2InfoMap.put(
								splittedLine[0],
								new FileInfo(splittedLine[0], null, source)
						);
					} else if (phase.equals(Constants.LOG_PHASE_2)) {
						fileName2DecisionMap.put(splittedLine[0],
								splittedLine[2]);
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

	/**
	 * The main method of running the server.
	 * It will first try to recover from log file.
	 * Then it will start the main loop. But we choose to use
	 * call back to handle the message.
	 *
	 * @param args the port number of the server.
	 * @throws Exception if the number of arguments is not 1.
	 */
	public static void main (String args[]) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");
		// check if need recovery at the very beginning:
		recoverFromFailure();
		Server srv = new Server();
		messageHandlingCallBack = new MessageHandlingCallBack();
		PL = new ProjectLib(Integer.parseInt(args[0]),
				srv, messageHandlingCallBack);

		// recover from failure:
		for (Map.Entry<String, FileInfo> entry : fileName2InfoMap.entrySet()) {
			String filename = entry.getKey();
			FileInfo fileInfo = entry.getValue();
			if (fileName2DecisionMap.containsKey(filename)) {
				String decision = fileName2DecisionMap.get(filename);
				TwoPCLogic twoPCLogic = new TwoPCLogic(
						fileInfo.fname, fileInfo.img, fileInfo.sources
				);
				twoPCLogic.setPL(PL);
				twoPCLogic.setMessageHandlingCallBack(messageHandlingCallBack);
				twoPCLogic.setCommitStatus(decision);
				twoPCLogic.startCommit();
			} else {
				TwoPCLogic twoPCLogic = new TwoPCLogic(
						fileInfo.fname, fileInfo.img, fileInfo.sources
				);
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
			System.out.println("Server: Got message from " + msg.addr);
		}
	}

	/**
	 * The inner class of Server which covers the main
	 * logic for 2PC protocol. The 2PC protocol is divided into
	 * three phases: prepare, decision and commit.
	 * The prepare phase is to send the prepare message to all user nodes
	 * and wait their response. The votes will be got from users and
	 * server will make a decision based on the votes.
	 * And send back to user nodes. Log the decision and flush to disk.
	 * Finally, send decision and wait for users ACK.
	 *
	 * @author Ruobing Wang ruobing2
	 */
	public static class TwoPCLogic implements Runnable{
		private String fname;
		private byte[] img;
		private String[] sources;
		private String commitStatus = Constants.INITIAL_COMMIT;
		private Thread thread;
		private ProjectLib PL;
		private MessageHandlingCallBack messageHandlingCallBack;
		ConcurrentHashMap<String, List<String>> sourceNode2FileName
				= new ConcurrentHashMap<>();

		/**
		 * Constructor of TwoPCLogic object.
		 *
		 * @param fname the file name.
		 * @param img the image of the file.
		 * @param sources the source nodes of the file.
		 */
		TwoPCLogic(String fname, byte[] img, String[] sources) {
			this.fname = fname;
			this.img = img;
			this.sources = sources;
		}

		/**
		 * Set the project lib.
		 *
		 * @param PL the project lib.
		 */
		public void setPL(ProjectLib PL) {
			this.PL = PL;
		}

		/**
		 * Set the message handling call back.
		 *
		 * @param messageHandlingCallBack the message handling call back.
		 */
		public void setMessageHandlingCallBack(
				MessageHandlingCallBack messageHandlingCallBack) {
			this.messageHandlingCallBack = messageHandlingCallBack;
		}

		/**
		 * Set the commit status.
		 *
		 * @param commitStatus the commit status that will be used
		 *                     to communicate with user nodes.
		 */
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
				sourceNode2FileName.computeIfAbsent(sourceNode,
						key -> new ArrayList<>()).add(fileName);
			}
			if (this.commitStatus.equals(Constants.INITIAL_COMMIT)) {
				while (!isRecoveryCompleted) {
					// wait until the recovery is completed.
					try {
						Thread.sleep(Constants.THREAD_SLEEP_TIME);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				startPhasePrepare();
			} else if (this.commitStatus.equals(Constants.DECISION_ABORT)) {
				logDecision();
			}
			startPhaseCommit();
			logConclusion();
		}

		/**
		 * Start the 2PC protocol phase prepare.
		 * It includes three steps:
		 * 1. Ask for votes.
		 * 2. Get the votes.
		 * 3. Log the decision.
		 */
		private void startPhasePrepare() {
			// Ask for votes:
			askAllNodesForVotes();
			// Get Reply
			getDecision();
			// log decision:
			logDecision();
		}

		/**
		 * Start the 2PC protocol phase commit.
		 * It includes sending decision to all user nodes.
		 */
		private void startPhaseCommit() {
			// send decision until ACK
			sendDecisionUntilAck();
		}

		/**
		 * The main logic of sending decision to all user nodes.
		 * Sends a decision message to all participants
		 * until an ACK message is received from each participant.
		 */
		private void sendDecisionUntilAck() {
			String updatedCommitStatus =
					commitStatus.equals(Constants.DECISION_COMMIT) ?
					Constants.DECISION_COMMIT : Constants.DECISION_ABORT;
			messageHandlingCallBack.initializeMessageQueue(fname);
			informParticipants(updatedCommitStatus);

			while (!sourceNode2FileName.isEmpty()) {
				long startTime = System.currentTimeMillis();
				while (System.currentTimeMillis() - startTime
						< Constants.TIMEOUT) {
					if (!messageHandlingCallBack
							.getMessageQueue(fname)
							.isEmpty()) {
						ProjectLib.Message msg = messageHandlingCallBack
								.getMessageQueue(fname).poll();
						try {
							assert msg != null;
							AdvancedMessage receivedMessage =
									AdvancedMessageSerializer
											.deserialize(msg.body);
							if (receivedMessage
									.getMode()
									.equals(Constants.ACK)) {
								sourceNode2FileName.remove(msg.addr);
							}
						} catch (IOException | ClassNotFoundException e) {
							e.printStackTrace();
						}
					}
				}
				informParticipants(updatedCommitStatus);
			}
			messageHandlingCallBack.removeMessageQueue(fname);
		}

		/**
		 * Informs all participants of the updated commit
		 * status for the current file.
		 *
		 * @param updatedCommitStatus the updated commit status.
		 */
		private void informParticipants(String updatedCommitStatus) {
			for (Map.Entry<String, List<String>> entry :
					sourceNode2FileName.entrySet()) {
				String sourceNode = entry.getKey();
				List<String> fileNames = entry.getValue();
				String[] sources = fileNames.toArray(new String[0]);
				AdvancedMessage advancedMessage = new AdvancedMessage(
						fname,
						sources,
						null,
						updatedCommitStatus
				);
				try {
					byte[] message = AdvancedMessageSerializer
							.serialize(advancedMessage);
					ProjectLib.Message msg =
							new ProjectLib.Message(sourceNode, message);
					PL.sendMessage(msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		/**
		 * Sends a PREPARE message to all participants,
		 * asking for their votes for the current transaction.
		 */
		private void askAllNodesForVotes() {
			for (Map.Entry<String, List<String>> entry :
					sourceNode2FileName.entrySet()) {
				String sourceNode = entry.getKey();
				List<String> fileNames = entry.getValue();
				String[] sources = fileNames.toArray(new String[0]);
				AdvancedMessage advancedMessage = new AdvancedMessage(
						fname,
						sources,
						img,
						Constants.PREPARE
				);
				try {
					byte[] message = AdvancedMessageSerializer
							.serialize(advancedMessage);
					ProjectLib.Message msg =
							new ProjectLib.Message(sourceNode, message);
					PL.sendMessage(msg);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		/**
		 * Waits for votes from all participants and determines
		 * whether the transaction should be committed or aborted.
		 */
		private void getDecision() {
			// case1: at least one node says no or times out: abort
			long startTime = System.currentTimeMillis();
			int agreeUsers = 0;
			while (System.currentTimeMillis() - startTime < Constants.TIMEOUT
					&& agreeUsers < sourceNode2FileName.size()) {
				if (!messageHandlingCallBack
						.getMessageQueue(fname).isEmpty()) {
					ProjectLib.Message msg = messageHandlingCallBack
							.getMessageQueue(fname)
							.poll();
					try {
						assert msg != null;
						AdvancedMessage advancedMessage =
								AdvancedMessageSerializer
										.deserialize(msg.body);
						if (advancedMessage
								.getMode()
								.equals(Constants.REFUSE)) {
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
				String targetPath = System.getProperty("user.dir")
						+ File.separator + fname;
				try {
					FileOutputStream fos = new FileOutputStream(targetPath);
					fos.write(img);
					fos.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		/**
		 * Starts the commit process for the current transaction
		 * by initializing a message queue for the current file,
		 * logging the list of servers,
		 * and starting a new thread for the transaction.
		 */
		public void startCommit() {
			messageHandlingCallBack.initializeMessageQueue(fname);
			logListOfServers();
			PL.fsync();
			if (thread == null) {
				thread = new Thread(this);
				thread.start();
			}
		}

		/**
		 * Logs the list of nodes for the current file.
		 */
		private void logListOfServers() {
			String targetPath = System.getProperty("user.dir")
					+ File.separator;
			Path logFilePath = Paths.get(targetPath,
					Constants.SEVER_LOG_FILE_NAME);
			String sourcesString = String.join(" ",
					Arrays.stream(sources).toArray(String[]::new));
			String logEntry = fname + " " +
					Constants.LOG_PHASE_1 + " " + sourcesString + "\n";

			try (BufferedWriter logWriter = Files.newBufferedWriter(
					logFilePath,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				PL.fsync();
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}

		/**
		 * Log the decision based on the votes and flush to disk.
		 */
		private synchronized void logDecision() {
			String targetPath = System.getProperty("user.dir")
					+ File.separator;
			Path logFilePath = Paths.get(targetPath,
					Constants.SEVER_LOG_FILE_NAME);
			String logEntry = fname + " " +
					Constants.LOG_PHASE_2 + " " + commitStatus + "\n";

			try (BufferedWriter logWriter = Files.newBufferedWriter(
					logFilePath,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				PL.fsync();
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}

		/**
		 * Log the Conclusion of the 2PC protocol.
		 */
		private void logConclusion() {
			String targetPath = System.getProperty("user.dir") +
					File.separator;
			Path logFilePath = Paths.get(targetPath,
					Constants.SEVER_LOG_FILE_NAME);
			String logEntry = fname + " " + Constants.LOG_PHASE_3 + "\n";
			try (BufferedWriter logWriter = Files.newBufferedWriter(
					logFilePath,
					StandardOpenOption.CREATE,
					StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				PL.fsync();
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}
	}
}

