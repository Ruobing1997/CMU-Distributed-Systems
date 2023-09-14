import java.rmi.Remote;
import java.rmi.RemoteException;

public interface CoordinatorRMI extends Remote {
//    void addVM2Map(int vmId, int tierNum) throws RemoteException;

    boolean removeVMFromMap(int vmId) throws RemoteException;

    int getTierNum(int vmId) throws RemoteException;

    void addRequestToMasterQ(Cloud.FrontEndOps.Request request) throws RemoteException;

    Cloud.FrontEndOps.Request pollRequestFromMasterQ() throws RemoteException;

    int getMasterQLength() throws RemoteException;

    int getFrontTierCounter() throws RemoteException;

    int getMidTierCounter() throws RemoteException;

    void coordinatorScaleOut(int targetTier) throws RemoteException;
}
