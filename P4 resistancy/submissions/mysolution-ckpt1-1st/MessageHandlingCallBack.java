import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MessageHandlingCallBack implements ProjectLib.MessageHandling {
    ConcurrentHashMap<String, Queue<ProjectLib.Message>> fname2MessageQueue = new ConcurrentHashMap<>();
    @Override
    public boolean deliverMessage(ProjectLib.Message msg) {
        try {
            AdvancedMessage advancedMessage = AdvancedMessageSerializer.deserialize(msg.body);
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

    public Queue<ProjectLib.Message> getMessageQueue(String filename) {
        return fname2MessageQueue.get(filename);
    }

    public void initializeMessageQueue(String filename) {
        fname2MessageQueue.put(filename, new ConcurrentLinkedQueue<>());
    }

    public void removeMessageQueue(String filename) {
        fname2MessageQueue.remove(filename);
    }
}
