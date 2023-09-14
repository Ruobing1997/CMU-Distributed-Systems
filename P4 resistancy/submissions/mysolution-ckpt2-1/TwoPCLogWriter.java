import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class TwoPCLogWriter {
    private String targetPath = System.getProperty("user.dir") + File.separator;
    private Path logFilePath;
    private String logEntry;

    TwoPCLogWriter(String logFileName) {
        this.logFilePath = Paths.get(targetPath, logFileName);
    }

    public String phase1Log(String fname, String sourcesString) {
        logEntry = fname + " " + Constants.LOG_PHASE_1 + " " + sourcesString + "\n";
        return logEntry;
    }

    public String phase2Log(String fname, String commitStatus) {
        logEntry = fname + " " + Constants.LOG_PHASE_2 + " " + commitStatus + "\n";
        return logEntry;
    }

    public String phase3Log(String fname) {
        logEntry = fname + " " + Constants.LOG_PHASE_3 + "\n";
        return logEntry;
    }

    public void writeLog() {
        try (BufferedWriter logWriter = Files.newBufferedWriter(logFilePath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            logWriter.write(logEntry);
            logWriter.flush();
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }
}
