import java.io.Serializable;

public class AdvancedMessage implements Serializable {
    private String fname;
    private String[] sources;
    private byte[] img;
    private String mode;

    public AdvancedMessage(String fname, String[] sources, byte[] img, String mode) {
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

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String toString() {
        return "AdvancedMessage [fname=" + fname + ", sources=" + sources + ", img=" + img + ", mode=" + mode + "]";
    }
}
