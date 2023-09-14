public class FileInfo {
    public String fname;
    public byte[] img;
    public String[] sources;
    public String commitStatus = Constants.INITIAL_COMMIT;

    FileInfo(String fname, byte[] img, String[] sources) {
        this.fname = fname;
        this.img = img;
        this.sources = sources;
    }
}
