/* Skeleton code for UserNode */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UserNode is the class for clients. It communicates with the server with
 * 2PC protocol. It first votes yes or no to the server. And send back the vote.
 * Then it waits the server to accept commit or abort decision.
 *
 * @author Ruobing Wang ruobing2
 */
public class UserNode implements ProjectLib.MessageHandling {
	public final String myId;
	public static ProjectLib PL;
	private static Map<String, Object> fileLockMap
			= new ConcurrentHashMap<>();
	private static Map<String, String> fileName2DecisionMap
			= new ConcurrentHashMap<>();

	public UserNode( String id ) {
		myId = id;
	}

	/**
	 * Callback called when message received.
	 * Argument is the message, including source address and body.
	 * Function should return True if it handled the message,
	 * or False if it did not.
	 * On False, the message is added to the received queue,
	 * and can be delivered to a getMessage call.
	 * It includes 3 modes: prepare, commit, abort.
	 *
	 * @param msg the message received.
	 * @return true if the message is handled, false otherwise.
	 */
	public boolean deliverMessage(ProjectLib.Message msg) {
		try {
			AdvancedMessage serverMessage =
					AdvancedMessageSerializer.deserialize(msg.body);
			String mode = serverMessage.getMode();
			if (mode.equals(Constants.PREPARE)) {
				// vote yes or no
				// if yes: log vote. Lock data
				AdvancedMessage result = userVote(serverMessage);
				ProjectLib.Message userMessage =
						generateMessageFromReply(msg, result);
				PL.sendMessage(userMessage);
				return true;
			} else if (mode.equals(Constants.DECISION_COMMIT)) {
				AdvancedMessage result =
						sendACKToServer(serverMessage, true);
				ProjectLib.Message userMessage =
						generateMessageFromReply(msg, result);
				PL.sendMessage(userMessage);
				return true;
			} else if (mode.equals(Constants.DECISION_ABORT)) {
				AdvancedMessage result =
						sendACKToServer(serverMessage, false);
				ProjectLib.Message userMessage =
						generateMessageFromReply(msg, result);
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

	/**
	 * Sends an ACK message to the Server
	 * and performs cleanup actions for the current transaction.
	 *
	 * @param serverMessage the message received from the server.
	 * @param isCommitted whether the transaction was committed or aborted.
	 * @return the ACK message to be sent to the server.
	 */
	private AdvancedMessage sendACKToServer(
			AdvancedMessage serverMessage, boolean isCommitted) {
		String fname = serverMessage.getFname();
		String[] sources = serverMessage.getSources();

		for (String source : sources) {
			String targetPath = System.getProperty("user.dir")
					+ File.separator;
			Path logFilePath = Paths.get(targetPath,
					Constants.CLIENT_LOG_FILE_NAME);
			String logEntry = source + " " +
					Constants.LOG_PHASE_2 + " " +
					serverMessage.getMode() + "\n";
			try (BufferedWriter logWriter = Files.newBufferedWriter(
					logFilePath,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				logWriter.write(logEntry);
				logWriter.flush();
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}

		for (String source : sources) {
			if (isCommitted) {
				// remove file
				String targetPath = System.getProperty("user.dir") +
						File.separator + source;
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

	/**
	 * Unlock the file.
	 *
	 * @param source the source to be unlocked.
	 */
	private static void unlockFile(String source) {
		fileLockMap.remove(source);
	}

	/**
	 * Log the Conclusion of the 2PC protocol.
	 *
	 * @param source the source to be logged.
	 */
	private static void logConclusion(String source) {
		String targetPath = System.getProperty("user.dir") + File.separator;
		Path logFilePath = Paths.get(targetPath, Constants.CLIENT_LOG_FILE_NAME);
		String logEntry = source + " " + Constants.LOG_PHASE_3 + " " + "\n";
		try (BufferedWriter logWriter = Files.newBufferedWriter(logFilePath,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			logWriter.write(logEntry);
			logWriter.flush();
			PL.fsync();
		} catch (IOException e) {
			e.printStackTrace(System.err);
		}
	}

	/**
	 * The logic of users to vote yes or no.
	 *
	 * @param serverMessage the message received from the server.
	 * @return
	 */
	private AdvancedMessage userVote(AdvancedMessage serverMessage) {
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

	/**
	 * Log the agreement based on the user's vote.
	 *
	 * @param fname the file name.
	 * @param sources the sources to be logged.
	 */
	private void logAgreement(String fname, String[] sources) {
		String targetPath = System.getProperty("user.dir") + File.separator;
		Path logFilePath = Paths.get(targetPath,
				Constants.CLIENT_LOG_FILE_NAME);
		for (String source : sources) {
			String logEntry = source + " " +
					Constants.LOG_PHASE_1 + " " +
					fname + "\n";
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
	}

	/**
	 * Lock the file to prevent others from updating it.
	 *
	 * @param sources the sources to be locked.
	 */
	private void lockFiles(String[] sources) {
		for (String source : sources) {
			fileLockMap.put(source, new Object());
		}
	}

	/**
	 * Check if the file exists.
	 *
	 * @param filename the file name.
	 * @return true if the file exists, false otherwise.
	 */
	private boolean fileExists(String filename) {
		File file = new File(filename);
		return file.exists();
	}

	/**
	 * Generate a message based on message content and result.
	 *
	 * @param msg message content to be concatenated with result.
	 * @param result result to be concatenated with message content.
	 * @return ProjectLib.Message object for sending.
	 * @throws IOException if serialization fails.
	 */
	private ProjectLib.Message generateMessageFromReply(
			ProjectLib.Message msg,
			AdvancedMessage result) throws IOException {
		byte[] userMessage = AdvancedMessageSerializer.serialize(result);
		return new ProjectLib.Message(msg.addr, userMessage);
	}

	/**
	 * UserNode side recovery logic.
	 * Recovers the application from a failure
	 * by reading the log file and reprocessing incomplete transactions.
	 * Retrieves the log file from the user's current directory.
	 * If the log file exists, it reads the file line by line.
	 * Depending on the phase specified in the log line,
	 * the method updates the fileLockMap and fileName2DecisionMap.
	 * Iterates through the fileName2DecisionMap and processes
	 * the transactions accordingly:
	 * If the decision is 'accept', the file is deleted.
	 * Regardless of the decision, the file is unlocked,
	 * and the conclusion is logged.
	 * If the log file does not exist, an error message is printed.
	 *
	 * @throws RuntimeException if an IOException occurs
	 * during file reading or deletion.
	 */
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

	/**
	 * The main method for the UserNode application.
	 * Validates the command line
	 * arguments for the correct number of inputs (port and id).
	 * Handle any incomplete transactions from
	 * previous failure at beginning.
	 *
	 * @param args command line arguments, port and id.
	 * @throws Exception if the number of arguments is not 2.
	 */
	public static void main (String args[]) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
		recoverFromFailure();
		UserNode UN = new UserNode(args[1]);
		PL = new ProjectLib(Integer.parseInt(args[0]), args[1], UN);

		ProjectLib.Message msg =
				new ProjectLib.Message("Server", "hello".getBytes());
		System.out.println( args[1] + ": Sending message to " + msg.addr );
	}
}

