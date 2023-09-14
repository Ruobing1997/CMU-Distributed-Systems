import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The MessageHandlingCallBack class is used for the callback function of
 * call back implementation for ProjectLib class.
 *
 * It implements the ProjectLib.MessageHandling
 * interface and provides methods for
 * handling messages received by the server.
 *
 * @author Ruobing Wang ruobing2
 */
public class MessageHandlingCallBack implements ProjectLib.MessageHandling {
    ConcurrentHashMap<String, Queue<ProjectLib.Message>>
            fname2MessageQueue = new ConcurrentHashMap<>();

    /**
     * The function will be triggered when server receives a message.
     *
     * @param msg the message received by the server.
     * @return true if the message is handled successfully, false otherwise.
     */
    @Override
    public boolean deliverMessage(ProjectLib.Message msg) {
        try {
            AdvancedMessage advancedMessage =
                    AdvancedMessageSerializer.deserialize(msg.body);
            String filename = advancedMessage.getFname();
            if (fname2MessageQueue.get(filename) != null) {
                fname2MessageQueue.get(filename).add(msg);
                return true;
            } else {
                return false;
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Get the message queue for a given file.
     *
     * @param filename the name of the file.
     * @return the message queue for the file.
     */
    public Queue<ProjectLib.Message> getMessageQueue(String filename) {
        return fname2MessageQueue.get(filename);
    }

    /**
     * Initialize the message queue for a given file.
     * @param filename the name of the file.
     */
    public void initializeMessageQueue(String filename) {
        fname2MessageQueue.put(filename, new ConcurrentLinkedQueue<>());
    }

    /**
     * Remove the message queue for a given file.
     * @param filename the name of the file.
     */
    public void removeMessageQueue(String filename) {
        fname2MessageQueue.remove(filename);
    }
}
