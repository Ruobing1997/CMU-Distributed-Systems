import java.net.MalformedURLException;
import java.rmi.AlreadyBoundException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The Server class serves contains two tiers of servers,
 * one is the front tier and the other is the middle tier.
 * The front tier servers are responsible for receiving
 * requests from load balancer and sending requests to
 * mid-tiers. The mid-tier connects with database for
 * responding clients.
 *
 * Created by Ruobing Wang at 2023-04-04
 * @author Ruobing Wang (ruobing2)
 */

public class Server extends UnicastRemoteObject implements CoordinatorRMI {
	// The server library for interacting with the cloud.
	public static ServerLib SL;

	// The server information.
	public static ServerInfo serverInfo;

	// The map from VM id to tier number to identify which.
	public static ConcurrentHashMap<Integer, Integer>
			VM2TierMap = new ConcurrentHashMap<>();

	// The linked list for storing the requests.
	public static BlockingQueue<Cloud.FrontEndOps.Request>
			masterQ = new LinkedBlockingQueue<>();

	// The counters for the number of front and mid-tier servers.
	public static int frontTierCounter = 0,
			midTierCounter = 0;

	// The previous time for scaling out.
	public static long prevScaleOutFrontTimeInMS = 0;

	// The time when master boot.
	public static long masterTimeBoot = 0;

	// The previous time for mid-tier process, front request process.
	public static long prevProcessRequestTime = 0,
			prevDealWithFrontRequestTime = 0,
			prevMasterArrivalTime = 0;

	// Record the number of requests that master received.
	public static int masterDealRequestCount = 0;

	// The number of requests that master received before scaling out.
	public static int requestDealThreshHold = 0;

	// To keep track of whether the server has handled the beginning.
	public static boolean visited = false;

	/**
	 * Creates and exports a new UnicastRemoteObject object using the
	 * particular supplied port.
	 *
	 * <p>The object is exported with a server socket
	 * created using the {@link RMISocketFactory} class.
	 *
	 * @throws RemoteException if failed to export object
	 * @since 1.2
	 */
	protected Server() throws RemoteException {
		super();
	}

	/**
	 * The method to bind the server to the cloud.
	 * And process master or slave.
	 * A master server is a coordinator server.
	 * A slaver server contains two types of servers,
	 * one is the front tier and the other is the middle tier.
	 *
	 * @param args The arguments for the server.
	 * @throws Exception The exception when the server fails.
	 */
	public static void main (String args[]) throws Exception {
		if (args.length != 3)
			throw new Exception("Need 3 args: " +
					"<cloud_ip> <cloud_port> <VM id>");
		serverInfo =
				new ServerInfo(args[0],
						Integer.parseInt(args[1]), Integer.parseInt(args[2]));
		SL = new ServerLib(serverInfo.getIp(), serverInfo.getPort());
		bindServer(serverInfo.getIp(),
				serverInfo.getPort(),
				serverInfo.getVmId());

		if (serverInfo.getVmId() == Constants.MASTER_ID) {
			processMaster();
		} else {
			processSlave();
		}
	}

	/**
	 * The logic of processing the slave servers.
	 */
	private static void processSlave() {
		CoordinatorRMI coordinator = lookUpServer(Constants.MASTER_ID);
		try {
			assert coordinator != null;
			if (coordinator.getTierNum(serverInfo.getVmId())
					== Constants.FRONT_TIER) {
				processFront(coordinator);
			} else if (coordinator.getTierNum(serverInfo.getVmId())
					== Constants.MID_TIER) {
				processMid(coordinator);
			}
		} catch (RemoteException ignored) {
		}
	}

	/**
	 * The logic of processing the front tier servers.
	 *
	 * @param coordinator The coordinator server.
	 */
	private static void processFront(CoordinatorRMI coordinator) {
		SL.register_frontend();
		while (true) {
			try {
				long curTime = System.currentTimeMillis();
				int frontTierServerCount = coordinator.getFrontTierCounter();
				if (scaleInFrontTierDetect(curTime, frontTierServerCount)) {
					// do scale in job
					boolean isRemoved =
							coordinator.removeVMFromMap(serverInfo.getVmId());
					if (isRemoved) {
						SL.shutDown();
					}
				} else {
					try {
						Cloud.FrontEndOps.Request r = SL.getNextRequest();
						coordinator.addRequestToMasterQ(r);
						prevDealWithFrontRequestTime = curTime;
					} catch (RemoteException ignored) {
					}
				}
			} catch (RemoteException ignored) {
			}
		}
	}

	/**
	 * The logic of processing the master server.
	 *
	 * @param curTime The current time.
	 * @param frontTierServerCount The number of front tier servers.
	 * @return Whether to scale in the front tier.
	 */
	private static boolean scaleInFrontTierDetect(long curTime,
												  int frontTierServerCount) {
		long timeInterval = curTime - prevDealWithFrontRequestTime;
		return prevDealWithFrontRequestTime != 0
				&& timeInterval > Constants.FRONT_TIER_NO_JOB_TIME
				&& frontTierServerCount > Constants.MIN_FRONT_TIER_SIZE;
	}

	/**
	 * The logic of processing the middle tier servers.
	 *
	 * @param coordinator The coordinator server.
	 */
	private static void processMid(CoordinatorRMI coordinator) {
		while (true) {
			try {
				long curTime = System.currentTimeMillis();
				int midTierServerCount = coordinator.getMidTierCounter();
				if (scaleInMidTierDetect(curTime, midTierServerCount)) {
					boolean isRemoved =
							coordinator.removeVMFromMap(serverInfo.getVmId());
					if (isRemoved) {
						SL.shutDown();
						System.exit(0);
					}
				} else {
					int QLen = coordinator.getMasterQLength();
					Cloud.FrontEndOps.Request r =
							coordinator.pollRequestFromMasterQ();
					if (r != null) {
						processMidRequests(
								coordinator,
								curTime,
								midTierServerCount,
								QLen,
								r
						);
					} else {
						if (prevProcessRequestTime == 0) {
							prevProcessRequestTime = curTime;
						}
					}
				}
			} catch (RemoteException ignored) {
			}
		}
	}

	/**
	 * The logic of processing the middle tier servers.
	 *
	 * @param coordinator The coordinator server.
	 * @param curTime The current time.
	 * @param midTierServerCount The number of middle tier servers.
	 * @param QLen The length of the master queue.
	 * @param r The request.
	 * @throws RemoteException The exception when the server fails.
	 */
	private static void processMidRequests(CoordinatorRMI coordinator,
										   long curTime,
										   int midTierServerCount,
										   int QLen,
										   Cloud.FrontEndOps.Request r)
			throws RemoteException {
		updateFastRequests();
		if (requestNeedDrop()) {
			SL.drop(r);
			requestDealThreshHold = 0;
		} else {
			if (scaleOutMidTierDetect(QLen,
					midTierServerCount)) {
				coordinator.coordinatorScaleOut(
						Constants.MID_TIER);
				SL.drop(r);
			} else {
				SL.processRequest(r);
				prevProcessRequestTime = curTime;
			}
		}
	}

	/**
	 * Update for fast requests.
	 */
	private static void updateFastRequests() {
		if (isRequestFast()) {
			requestDealThreshHold++;
		} else {
			requestDealThreshHold = 0;
		}
	}

	/**
	 * Check if the request is fast.
	 *
	 * @return Whether the request is fast.
	 */
	private static boolean isRequestFast() {
		return System.currentTimeMillis() - prevProcessRequestTime
				< Constants.CONFIG_FAST_REQUEST_RATE;
	}

	/**
	 * Check if the request needs to be dropped.
	 *
	 * @return Whether the request needs to be dropped.
	 */
	private static boolean requestNeedDrop() {
		return requestDealThreshHold >
				Constants.CONFIG_DROP_FAST_REQUEST_UP_LIMIT;
	}

	/**
	 * Detect if the middle tier needs to scale in.
	 *
	 * @param curTime The current time.
	 * @param midTierServerCount The number of middle tier servers.
	 * @return Whether the middle tier needs to scale in.
	 */
	private static boolean scaleInMidTierDetect(long curTime,
												int midTierServerCount) {
		long timeInterval = curTime - prevProcessRequestTime;
		return prevProcessRequestTime != 0
				&& timeInterval > Constants.MID_TIER_NO_JOB_TIME
				&& midTierServerCount > Constants.MIN_MID_TIER_SIZE;
	}

	/**
	 * Detect if the middle tier needs to scale out.
	 *
	 * @param QLen The length of the master queue.
	 * @param midTierServerCount The number of middle tier servers.
	 * @return Whether the middle tier needs to scale out.
	 */
	private static boolean scaleOutMidTierDetect(int QLen,
												 int midTierServerCount) {
		return QLen > Constants.CONFIG_MID_SCALE_OUT * midTierServerCount;
	}

	/**
	 * Look up the server via the target id.
	 *
	 * @param targetId The target id.
	 * @return The server.
	 */
	private static CoordinatorRMI lookUpServer(int targetId) {
		try {
			return (CoordinatorRMI) Naming.lookup(
					"//" + serverInfo.getIp() + ":" +
							serverInfo.getPort() +
							"/" + targetId
			);
		} catch (NotBoundException |
				MalformedURLException |
				RemoteException e) {
			return null;
		}
	}

	/**
	 * The logic of processing the master server.
	 */
	private static void processMaster() {
		// The Master server processes:
		// itself should be a frontend
		SL.register_frontend();
		frontTierCounter++;
		VM2TierMap.put(serverInfo.getVmId(), Constants.FRONT_TIER);
		// play as a coordinator
		// add servers as a front tier and a mid tier.
		scaleOut(Constants.MID_TIER);
		scaleOut(Constants.FRONT_TIER);
		masterDealRequestCount = 0;
		masterTimeBoot = System.currentTimeMillis();
		while (true) {
			if (SL.getStatusVM(Constants.FIRST_EXTRA_VM_ID)
					.equals(Cloud.CloudOps.VMStatus.Booting)) {
				dealWithBeginning();
			} else {
				int QLen = SL.getQueueLength();
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
				masterQ.add(r);
				if (prevScaleOutFrontTimeInMS == 0)
					prevScaleOutFrontTimeInMS = System.currentTimeMillis();
				if (scaleOutFrontTierDetect(QLen * frontTierCounter,
						frontTierCounter)) {
					scaleOut(Constants.FRONT_TIER);
					prevScaleOutFrontTimeInMS = System.currentTimeMillis();
				}
			}
		}
	}

	/**
	 * Deal when the server just start.
	 */
	private static void dealWithBeginning() {
		// drop or process requests
		long timeInterval = System.currentTimeMillis() - masterTimeBoot;
		if (SL.getQueueLength() > 1 && !visited) {
			long arrivalRate = timeInterval / SL.getQueueLength();
			if (arrivalRate < Constants.CONFIG_ARRIVAL_RATE_SCALE_OUT) {
				for (int i = 0;
					 i < Constants.CONFIG_MASTER_BOOT_FRONT_END; i++) {
					scaleOut(Constants.FRONT_TIER);
				}
				for (int i = 0;
					 i < Constants.CONFIG_MASTER_BOOT_MID_END; i++) {
					scaleOut(Constants.MID_TIER);
				}
			}
			visited = true;
		}
		if (masterDealRequestCount
				< Constants.CONFIG_MASTER_DEAL_REQUEST_COUNT_UP_LIMIT) {
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			SL.processRequest(r);
			masterDealRequestCount++;
			if (needDropWhenMasterBoot(prevMasterArrivalTime)) {
				SL.dropHead();
			}
			prevMasterArrivalTime = System.currentTimeMillis();
		} else {
			SL.dropHead();
		}
	}

	/**
	 * Check if the request needs to be dropped when the master boot.
	 *
	 * @param prevMasterArrivalTime The previous arrival time of the master.
	 * @return Whether the request needs to be dropped.
	 */
	private static boolean needDropWhenMasterBoot(long prevMasterArrivalTime) {
		if (prevMasterArrivalTime == 0) return false;
		else return System.currentTimeMillis() - prevMasterArrivalTime
				< Constants.CONFIG_DROP_TIME_WHEN_BOOT;
	}

	/**
	 * Detect if the front tier needs to scale out.
	 *
	 * @param QLen The length of the master queue.
	 * @param frontTierCounter The number of front tier servers.
	 * @return Whether the front tier needs to scale out.
	 */
	private static boolean scaleOutFrontTierDetect(int QLen,
												   int frontTierCounter) {
		return System.currentTimeMillis() - prevScaleOutFrontTimeInMS
				> Constants.CONFIG_COOL_DOWN_FRONT
				&& QLen > Constants.CONFIG_FRONT_SCALE_OUT * frontTierCounter;
	}

	/**
	 * Scale out the server.
	 *
	 * @param tierNum The tier number.
	 */
	private static void scaleOut(int tierNum) {
		int vmId = SL.startVM();
		VM2TierMap.put(vmId, tierNum);
		if (tierNum == Constants.FRONT_TIER) {
			frontTierCounter++;
		} else if (tierNum == Constants.MID_TIER) {
			midTierCounter++;
		}
	}

	/**
	 * Bind the server with the given ip, port and vm id.
	 *
	 * @param ip IP address.
	 * @param port Port number.
	 * @param vmId VM id.
	 */
	private static void bindServer(String ip, int port, int vmId) {
		try {
			Server server = new Server();
			Naming.bind("//" + ip + ":" +
					port +
					"/" + vmId, server);
		} catch (RemoteException |
				MalformedURLException |
				AlreadyBoundException ignored) {
		}
	}

	/**
	 * Remove VM from map.
	 *
	 * @param vmId VM id.
	 * @return true if remove successfully, false otherwise.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public synchronized boolean removeVMFromMap(int vmId)
			throws RemoteException {
		if (VM2TierMap.containsKey(vmId)) {
			int targetTier = VM2TierMap.remove(vmId);
			if (targetTier == Constants.FRONT_TIER) {
				frontTierCounter--;
			} else if (targetTier == Constants.MID_TIER) {
				midTierCounter--;
			}
			return true;
		}
		return false;
	}

	/**
	 * Get the tier number of the given VM id.
	 *
	 * @param vmId VM id.
	 * @return The tier number.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public int getTierNum(int vmId) throws RemoteException {
		return VM2TierMap.getOrDefault(vmId, -1);
	}

	/**
	 * Add request to master queue.
	 *
	 * @param request request from load balancer.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public void addRequestToMasterQ(Cloud.FrontEndOps.Request request)
			throws RemoteException {
		masterQ.add(request);
	}

	/**
	 * Poll request from master queue.
	 *
	 * @return request from master queue.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public Cloud.FrontEndOps.Request pollRequestFromMasterQ()
			throws RemoteException {
		return masterQ.poll();
	}

	/**
	 * Get the length of master queue.
	 *
	 * @return length of master queue.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public int getMasterQLength() throws RemoteException {
		return masterQ.size();
	}

	/**
	 * Get the number of VMs as front tiers.
	 *
	 * @return number of VMs as front tiers.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public int getFrontTierCounter() throws RemoteException {
		return frontTierCounter;
	}

	/**
	 * Get the number of VMs as mid-tiers.
	 *
	 * @return number of VMs as mid-tiers.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public int getMidTierCounter() throws RemoteException {
		return midTierCounter;
	}

	/**
	 * Scale out as RMI call.
	 *
	 * @param targetTier target tier to scale out.
	 * @throws RemoteException if RMI error occurs.
	 */
	@Override
	public void coordinatorScaleOut(int targetTier) throws RemoteException {
		scaleOut(targetTier);
	}
}
