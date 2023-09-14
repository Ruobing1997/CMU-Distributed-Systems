import java.io.Serializable;

/**
 * The AdvancedMessage class is used to do
 * conversation between the client and the server.
 * It implements the Serializable interface, for simulating
 * the serialized object in RMI.
 *
 * The file includes the information for the provided file with
 * name, sources, image, and mode (commit status).
 *
 * @author Ruobing Wang ruobing2
 */
public class AdvancedMessage implements Serializable {
    private String fname;
    private String[] sources;
    private byte[] img;
    private String mode;

    public AdvancedMessage(String fname, String[] sources,
                           byte[] img, String mode) {
        this.fname = fname;
        this.sources = sources;
        this.img = img;
        this.mode = mode;
    }

    public String getFname() {
        return fname;
    }

    public void setFname(String fname) {
        this.fname = fname;
    }

    public String[] getSources() {
        return sources;
    }

    public void setSources(String[] sources) {
        this.sources = sources;
    }

    public byte[] getImg() {
        return img;
    }

    public void setImg(byte[] img) {
        this.img = img;
    }

    public String getMode() {
        return mode;
    }

    public String toString() {
        return "AdvancedMessage [fname=" + fname +
                ", sources=" + sources + ", img=" + img +
                ", mode=" + mode + "]";
    }
}
