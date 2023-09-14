public class ServerInfo {
    private String ip;
    private int port;
    private int vmId;

    ServerInfo(String ip, int port, int vmId) {
        this.ip = ip;
        this.port = port;
        this.vmId = vmId;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public int getVmId() {
        return vmId;
    }
}
