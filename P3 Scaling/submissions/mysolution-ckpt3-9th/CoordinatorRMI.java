import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Coordinator RMI interface.
 * This interface provides calls including:
 * 1. Remove VM from map.
 * 2. Get the tier number of a VM from the map.
 * 3. Add request to master queue.
 * 4. Poll request from master queue.
 * 5. Get the number of servers as front tiers.
 * 6. Get the number of servers as mid tiers.
 * 7. Scale out for target tiers.
 * @author Ruobing Wang (ruobing2)
 */
public interface CoordinatorRMI extends Remote {
    /**
     * Remove VM from map.
     *
     * @param vmId VM id.
     * @return true if remove successfully, false otherwise.
     * @throws RemoteException if RMI error occurs.
     */
    boolean removeVMFromMap(int vmId) throws RemoteException;

    /**
     * Get the tier number of a VM from the map.
     *
     * @param vmId VM id.
     * @return tier number.
     * @throws RemoteException if RMI error occurs.
     */
    int getTierNum(int vmId) throws RemoteException;

    /**
     * Add request to master queue.
     *
     * @param request request from load balancer.
     * @throws RemoteException if RMI error occurs.
     */
    void addRequestToMasterQ(Cloud.FrontEndOps.Request request)
            throws RemoteException;

    /**
     * Poll request from master queue.
     *
     * @return request from master queue.
     * @throws RemoteException if RMI error occurs.
     */
    Cloud.FrontEndOps.Request pollRequestFromMasterQ()
            throws RemoteException;

    /**
     * Get the length of master queue.
     *
     * @return length of master queue.
     * @throws RemoteException if RMI error occurs.
     */
    int getMasterQLength() throws RemoteException;

    /**
     * Get the number of VMs as front tiers.
     *
     * @return number of VMs as front tiers.
     * @throws RemoteException if RMI error occurs.
     */
    int getFrontTierCounter() throws RemoteException;

    /**
     * Get the number of VMs as mid-tiers.
     *
     * @return number of VMs as mid-tiers.
     * @throws RemoteException if RMI error occurs.
     */
    int getMidTierCounter() throws RemoteException;

    /**
     * Scale out as RMI call.
     *
     * @param targetTier target tier to scale out.
     * @throws RemoteException if RMI error occurs.
     */
    void coordinatorScaleOut(int targetTier) throws RemoteException;
}
