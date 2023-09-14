import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 *  The AdvancedMessageSerializer class provides static methods
 *  for serializing and deserializing instances of the
 *  AdvancedMessage class to and from byte arrays.
 *  This class implements the Serializable interface, which allows
 *  instances of this class to be serialized by ObjectOutputStream
 *  and deserialized by ObjectInputStream.
 *
 * @author Ruobing Wang ruobing2
 */
public class AdvancedMessageSerializer implements Serializable {

    /**
     * Serializes an instance of the AdvancedMessage class to a byte array.
     *
     * @param message the AdvancedMessage instance to be serialized
     * @return a byte array that contains the serialized message
     * @throws IOException if there is an error during serialization
     */
    public static byte[] serialize(AdvancedMessage message)
            throws IOException {
        ByteArrayOutputStream byteArrayOutputStream =
                new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream =
                new ObjectOutputStream(byteArrayOutputStream);

        objectOutputStream.writeObject(message);
        objectOutputStream.close();
        byteArrayOutputStream.close();

        return byteArrayOutputStream.toByteArray();
    }

    /**
     * Deserializes an instance of the AdvancedMessage class
     * from a byte array.
     *
     * @param data the byte array that contains the serialized message.
     * @return the deserialized AdvancedMessage instance.
     * @throws IOException if there is an error during deserialization.
     * @throws ClassNotFoundException if the class of
     * the serialized object cannot be found.
     */
    public static AdvancedMessage deserialize(byte[] data)
            throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream =
                new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream =
                new ObjectInputStream(byteArrayInputStream);

        AdvancedMessage message =
                (AdvancedMessage) objectInputStream.readObject();
        objectInputStream.close();
        byteArrayInputStream.close();

        return message;
    }
}
