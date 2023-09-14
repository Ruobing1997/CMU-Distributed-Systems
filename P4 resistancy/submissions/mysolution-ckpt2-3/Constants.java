/**
 * The Constants class defines constant values used
 * throughout the server and user node.
 *
 * @author Ruobing Wang ruobing2
 */
public class Constants {
    public final static String SEVER_LOG_FILE_NAME = "log.txt";
    public final static String CLIENT_LOG_FILE_NAME = "log.txt";
    public final static String ADDR_FILE_DELIMETER = ":";
    public final static String INITIAL_COMMIT = "INITIAL_COMMIT";
    public static final String PREPARE = "ASK_FOR_VOTES";
    public static final long TIMEOUT = 6000;
    public static final String DECISION_ABORT = "DECISION_ABORT";
    public static final String REFUSE = "REFUSE";
    public static final String DECISION_COMMIT = "DECISION_COMMIT";
    public static final String ACK = "ACK";
    public static final String ACCEPT = "ACCEPT";
    public static final String LOG_PHASE_1 = "PREPARE";
    public static final String LOG_PHASE_2 = "DECISION";
    public static final String LOG_PHASE_3 = "DONE";
    public static final int THREAD_SLEEP_TIME = 1000;
}
