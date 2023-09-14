/* Skeleton code for UserNode */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserNode implements ProjectLib.MessageHandling {
	public final String myId;
	public static ProjectLib PL;
	private static Map<String, Object> fileLockMap = new ConcurrentHashMap<>();
	private static Map<String, String> fileName2DecisionMap = new ConcurrentHashMap<>();

	public UserNode( String id ) {
		myId = id;
	}

	public boolean deliverMessage( ProjectLib.Message msg ) {
		try {
			AdvancedMessage serverMessage = AdvancedMessageSerializer.deserialize(msg.body);
			String mode = serverMessage.getMode();
			System.err.println("Mode: " + mode);
			if (mode.equals(Constants.PREPARE)) {
				// vote yes or no
				// if yes: log vote. Lock data
				AdvancedMessage result = userVote(serverMessage);
				ProjectLib.Message userMessage = generateMessageFromReply(msg, result);
				PL.sendMessage(userMessage);
				return true;
			} else if (mode.equals(Constants.DECISION_COMMIT)) {
				AdvancedMessage result = sendACKToServer(serverMessage, true);
				ProjectLib.Message userMessage = generateMessageFromReply(msg, result);
				PL.sendMessage(userMessage);
				return true;
			} else if (mode.equals(Constants.DECISION_ABORT)) {
				AdvancedMessage result = sendACKToServer(serverMessage, false);
				ProjectLib.Message userMessage = generateMessageFromReply(msg, result);
				PL.sendMessage(userMessage);
				return true;
			} else {
				System.err.println("Unknown mode: " + mode);
				return false;
			}
		} catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
			return false;
		}
	}

	private AdvancedMessage sendACKToServer(AdvancedMessage serverMessage, boolean isCommitted) {
		System.err.println("**Sending ACK to server**");
		String fname = serverMessage.getFname();
		String[] sources = serverMessage.getSources();

		for (String source : sources) {
			String targetPath = System.getProperty("user.dir") + File.separator;
			Path logFilePath = Paths.get(targetPath, Constants.CLIENT_LOG_FILE_NAME);
			String logEntry = source + " " + Constants.LOG_PHASE_2 + " " + serverMessage.getMode() + "\n";
			try (BufferedWriter logWriter = Files.newBufferedWriter(logFilePath,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				System.err.println("Logged Decision");
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}

		for (String source : sources) {
			if (isCommitted) {
				// remove file
				String targetPath = System.getProperty("user.dir") + File.separator + source;
				Path path = Paths.get(targetPath);
				try {
					if (Files.deleteIfExists(path)) {
						System.err.println("Deleted file: " + source);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			unlockFile(source);
			logConclusion(source);
		}
		return new AdvancedMessage(
				fname,
				null,
				null,
				Constants.ACK
		);
	}

	private static void unlockFile(String source) {
		fileLockMap.remove(source);
	}

	private static void logConclusion(String source) {
		System.err.println("**Log Conclusion In User Node**");
		String targetPath = System.getProperty("user.dir") + File.separator;
		Path logFilePath = Paths.get(targetPath, Constants.CLIENT_LOG_FILE_NAME);
		String logEntry = source + " " + Constants.LOG_PHASE_3 + " " + "\n";
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


	private AdvancedMessage userVote(AdvancedMessage serverMessage) {
		System.err.println("**User Vote**");
		boolean valid = true;
		String fname = serverMessage.getFname();
		String[] sources = serverMessage.getSources();

		for (String source : sources) {
			if (fileLockMap.containsKey(source) || !fileExists(source)) {
				valid = false;
				break;
			}
		}

		boolean agree = false;

		if (valid) {
			lockFiles(sources);
			agree = PL.askUser(serverMessage.getImg(), sources);
			logAgreement(fname, sources);
		}

		return new AdvancedMessage(
				fname,
				null,
				null,
				agree ? Constants.ACCEPT : Constants.REFUSE
		);
	}

	private void logAgreement(String fname, String[] sources) {
		System.err.println("**Log Agreement In User Node**");
		// TODO: extract log in a class as an operation as singleton
		String targetPath = System.getProperty("user.dir") + File.separator;
		Path logFilePath = Paths.get(targetPath, Constants.CLIENT_LOG_FILE_NAME);
		for (String source : sources) {
			String logEntry = source + " " + Constants.LOG_PHASE_1 + " " + fname + "\n";
			try (BufferedWriter logWriter = Files.newBufferedWriter(logFilePath,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
				PL.fsync();
				System.err.println("Logged Agreement");
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}
	}

	private void lockFiles(String[] sources) {
		for (String source : sources) {
			fileLockMap.put(source, new Object());
		}
	}

	private boolean fileExists(String filename) {
		File file = new File(filename);
		return file.exists();
	}

	private ProjectLib.Message generateMessageFromReply(ProjectLib.Message msg, AdvancedMessage result) throws IOException {
		byte[] userMessage = AdvancedMessageSerializer.serialize(result);
		return new ProjectLib.Message(msg.addr, userMessage);
	}

	private static void recoverFromFailure() {
		String targetPath = System.getProperty("user.dir") + File.separator;
		Path logFilePath = Paths.get(targetPath, Constants.CLIENT_LOG_FILE_NAME);
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
					if (phase.equals(Constants.LOG_PHASE_1)) {
						fileLockMap.put(splittedLine[0], new Object());
					} else if (phase.equals(Constants.LOG_PHASE_2)) {
						fileName2DecisionMap.put(splittedLine[0], splittedLine[2]);
					} else if (phase.equals(Constants.LOG_PHASE_3)) {
						fileLockMap.remove(splittedLine[0]);
						fileName2DecisionMap.remove(splittedLine[0]);
					}
				}
				// TODO: redo?
				System.out.println("result map: " + fileName2DecisionMap);
				for (Map.Entry<String, String> entry : fileName2DecisionMap.entrySet()) {
					String fname = entry.getKey();
					String decision = entry.getValue();
					if (decision.equals(Constants.ACCEPT)) {
						// remove file
						String fileToRemove = System.getProperty("user.dir") + File.separator + fname;
						Path fileToRemovePath = Paths.get(fileToRemove);
						try {
							if (Files.deleteIfExists(fileToRemovePath)) {
								System.err.println("Deleted file: " + fname);
							}
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					unlockFile(fname);
					logConclusion(fname);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			System.err.println("No log file found");
		}
	}
	public static void main ( String args[] ) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		recoverFromFailure();
		UserNode UN = new UserNode(args[1]);
		PL = new ProjectLib( Integer.parseInt(args[0]), args[1], UN );

		ProjectLib.Message msg = new ProjectLib.Message( "Server", "hello".getBytes() );
		System.out.println( args[1] + ": Sending message to " + msg.addr );
	}
}

