/**
 * The FileInfo class represents information
 * about a file, including the file name, image data, and sources.
 * To cover the images provided in the server and user node.
 *
 * @author Ruobing Wang ruobing2
 */
public class FileInfo {
    public String fname;
    public byte[] img;
    public String[] sources;

    FileInfo(String fname, byte[] img, String[] sources) {
        this.fname = fname;
        this.img = img;
        this.sources = sources;
    }
}
