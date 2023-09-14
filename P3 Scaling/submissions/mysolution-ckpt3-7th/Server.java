/* Sample code for basic Server */

import java.io.FileOutputStream;
import java.io.PrintStream;
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

public class Server extends UnicastRemoteObject implements CoordinatorRMI {
	public static ServerLib SL;
	public static ServerInfo serverInfo;
	public static ConcurrentHashMap<Integer, Integer> VM2TierMap = new ConcurrentHashMap<>();
	public static BlockingQueue<Cloud.FrontEndOps.Request> masterQ = new LinkedBlockingQueue<>();
	public static int frontTierCounter = 0, midTierCounter = 0;
	public static long prevScaleOutFrontTimeInMS = 0, masterTimeBoot = 0;
	public static long prevProcessRequestTime = 0,
			prevDealWithFrontRequestTime = 0,
			prevMasterArrivalTime = 0;
	public static int masterDealRequestCount = 0;
	public static int requestDealThreshHold = 0;
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

	public static void main (String args[] ) throws Exception {
		// Cloud class will start one instance of this Server intially [runs as separate process]
		// It starts another for every startVM call [each a seperate process]
		// Server will be provided 3 command line arguments
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
//		PrintStream errorStream = new PrintStream(new FileOutputStream("./logs/error_log" +
//				Integer.parseInt(args[2]) +".txt"));
//		System.setErr(errorStream);
		// Initialize ServerLib.  Almost all server and cloud operations are done
		// through the methods of this class.  Please refer to the html documentation in ../doc
		serverInfo = new ServerInfo(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));

		SL = new ServerLib(serverInfo.getIp(), serverInfo.getPort());

//		System.err.println("======Server Info IP PORT ID: ======");
//		System.err.println(serverInfo.getIp() + " " + serverInfo.getPort() + " " + serverInfo.getVmId());
//		System.err.println("=====================================");

		bindServer(serverInfo.getIp(), serverInfo.getPort(), serverInfo.getVmId());

		if (serverInfo.getVmId() == Constants.MASTER_ID) {
//			System.err.println("<< Process the server as a Master server! >>");
			// it is the master server, responsible for coordination
			processMaster();
		} else {
//			System.err.println("<< Process the server as a Slave server! >>");
			// it is the slave server
			processSlave();
		}
	}

	private static void processSlave() {
		CoordinatorRMI coordinator = lookUpServer(Constants.MASTER_ID);
		try {
			assert coordinator != null;
			if (coordinator.getTierNum(serverInfo.getVmId()) == Constants.FRONT_TIER) {
				processFront(coordinator);
			} else if (coordinator.getTierNum(serverInfo.getVmId()) == Constants.MID_TIER) {
				processMid(coordinator);
			}
		} catch (RemoteException e) {
//			e.printStackTrace();
		}
	}

	private static void processFront(CoordinatorRMI coordinator) {
//		System.err.println("<< Process the server as a Front server! >>");
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
//						System.err.println("===Scale in the front tier===");
						SL.shutDown();
//						System.err.println("The vm: " + serverInfo.getVmId() + " is Removed!");
					}
				} else {
					try {
						Cloud.FrontEndOps.Request r = SL.getNextRequest();
						coordinator.addRequestToMasterQ(r);
						prevDealWithFrontRequestTime = curTime;
//						System.err.println("Front: add to Q " +
//								coordinator.getMasterQLength() + " front count: " + coordinator.getFrontTierCounter());
					} catch (RemoteException e) {
//						System.err.println("Failed to add to Q");
//						e.printStackTrace();
					}
				}
			} catch (RemoteException e) {
//				e.printStackTrace();
			}
		}
	}

	private static boolean scaleInFrontTierDetect(long curTime, int frontTierServerCount) {
		long timeInterval = curTime - prevDealWithFrontRequestTime;
		return prevDealWithFrontRequestTime != 0
				&& timeInterval > Constants.FRONT_TIER_NO_JOB_TIME
				&& frontTierServerCount > Constants.MIN_FRONT_TIER_SIZE;
	}

	private static void processMid(CoordinatorRMI coordinator) {
//		System.err.println("<< Process the server as a MID server! >>");
		while (true) {
			try {
				long curTime = System.currentTimeMillis();
				int midTierServerCount = coordinator.getMidTierCounter();
				if (scaleInMidTierDetect(curTime, midTierServerCount)) {
					boolean isRemoved =
							coordinator.removeVMFromMap(serverInfo.getVmId());
					if (isRemoved) {
						System.err.println("===Scale in the mid tier===");
						SL.shutDown();
						System.err.println("The vm: " + serverInfo.getVmId() + " is shut down!");
						System.exit(0);
					}
				} else {
					int QLen = coordinator.getMasterQLength();
					Cloud.FrontEndOps.Request r = coordinator.pollRequestFromMasterQ();
					if (r != null) {
						if (isRequestFast()) {
							requestDealThreshHold++;
						} else {
							requestDealThreshHold = 0;
						}
						if (requestNeedDrop()) {
							SL.drop(r);
							requestDealThreshHold = 0;
						} else {
							if (scaleOutMidTierDetect(QLen, midTierServerCount)) {
//								System.err.println("===Scale out mid tier===");
								coordinator.coordinatorScaleOut(Constants.MID_TIER);
								// TODO: drop request?
								SL.drop(r);
							} else {
								SL.processRequest(r);
								prevProcessRequestTime = curTime;
							}
						}
					} else {
						if (prevProcessRequestTime == 0) prevProcessRequestTime = curTime;
					}
				}
			} catch (RemoteException e) {
//				e.printStackTrace();
			}
		}
	}

	private static boolean isRequestFast() {
		return System.currentTimeMillis() - prevProcessRequestTime < Constants.CONFIG_FAST_REQUEST_RATE;
	}

	private static boolean requestNeedDrop() {
		return requestDealThreshHold > Constants.CONFIG_DROP_FAST_REQUEST_UP_LIMIT;
	}

	private static boolean scaleInMidTierDetect(long curTime, int midTierServerCount) {
		long timeInterval = curTime - prevProcessRequestTime;
//		if (prevProcessRequestTime != 0 && timeInterval > Constants.MID_TIER_NO_JOB_TIME
//				&& midTierServerCount > Constants.MIN_MID_TIER_SIZE) {
//			System.err.println("prevMidTierArrivalTime: " + prevProcessRequestTime + " curTime: " + curTime +
//					" timeInterval: " + timeInterval + " midTierServerCount: " + midTierServerCount);
//		}
//		System.err.println("prevMidTierArrivalTime: " + prevProcessRequestTime + " curTime: " + curTime +
//		" timeInterval: " + timeInterval + " midTierServerCount: " + midTierServerCount);
		return prevProcessRequestTime != 0
				&& timeInterval > Constants.MID_TIER_NO_JOB_TIME
				&& midTierServerCount > Constants.MIN_MID_TIER_SIZE;
	}

	private static boolean scaleOutMidTierDetect(int QLen, int midTierServerCount) {
//		System.err.println("QLen: " + QLen + " midTierServerCount: " + midTierServerCount +
//				" res: " + ((double)QLen) / midTierServerCount);
//		if (QLen > Constants.CONFIG_MID_SCALE_OUT * midTierServerCount) {
//			System.err.println("***Detect Scale Out Mid***");
//			System.err.println("QLen: " + QLen + " midTierServerCount: " + midTierServerCount);
//		}
		return QLen > Constants.CONFIG_MID_SCALE_OUT * midTierServerCount;
	}


	private static CoordinatorRMI lookUpServer(int targetId) {
		try {
//			System.err.println("Look up the server: " + "//" + serverInfo.getIp() + ":" +
//					serverInfo.getPort() +
//					"/" + targetId);
			return (CoordinatorRMI) Naming.lookup(
					"//" + serverInfo.getIp() + ":" +
							serverInfo.getPort() +
							"/" + targetId
			);
		} catch (NotBoundException | MalformedURLException | RemoteException e) {
//			e.printStackTrace();
			return null;
		}
	}

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
			if (SL.getStatusVM(Constants.FIRST_EXTRA_VM_ID).equals(Cloud.CloudOps.VMStatus.Booting)) {
				dealWithBeginning();
			} else {
				int QLen = SL.getQueueLength();
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
//				System.err.println("Master get request: " + r.item);
				masterQ.add(r);
//				System.err.println("Master: add to Q " +
//						masterQ.size() + " front count: " + frontTierCounter + "QLen: " + QLen);
				if (prevScaleOutFrontTimeInMS == 0) prevScaleOutFrontTimeInMS = System.currentTimeMillis();
				if (scaleOutFrontTierDetect(QLen * frontTierCounter, frontTierCounter)) {
//					System.err.println("Factor: " + ((double) SL.getQueueLength()) / frontTierCounter);
					scaleOut(Constants.FRONT_TIER);
					prevScaleOutFrontTimeInMS = System.currentTimeMillis();
				}
			}
		}
	}

	private static void dealWithBeginning() {
		// drop or process requests
		long timeInterval = System.currentTimeMillis() - masterTimeBoot;
		if (SL.getQueueLength() > 1 && !visited) {
			long arrivalRate = timeInterval / SL.getQueueLength();
			if (arrivalRate < Constants.CONFIG_ARRIVAL_RATE_SCALE_OUT) {
				System.err.println("!!!Request comes fast!!!");
				System.err.println("Current Arrival Rate: " + arrivalRate);
				for (int i = 0; i < Constants.CONFIG_MASTER_BOOT_FRONT_END; i++) {
//					System.err.println("===Scale out front tier While Begin===");
					scaleOut(Constants.FRONT_TIER);
				}
				for (int i = 0; i < Constants.CONFIG_MASTER_BOOT_MID_END; i++) {
//					System.err.println("===Scale out mid tier While Begin===");
					scaleOut(Constants.MID_TIER);
				}
			}
			visited = true;
		}
		if (masterDealRequestCount < Constants.CONFIG_MASTER_DEAL_REQUEST_COUNT_UP_LIMIT) {
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			SL.processRequest(r);
			masterDealRequestCount++;
			if (needDropWhenMasterBoot(prevMasterArrivalTime)) {
//				System.err.println("===Drop the next request===");
				SL.dropHead();
			}
			prevMasterArrivalTime = System.currentTimeMillis();
		} else {
//			System.err.println("===Over Count! Drop all the following requests===");
			SL.dropHead();
		}
	}

	private static boolean needDropWhenMasterBoot(long prevMasterArrivalTime) {
		if (prevMasterArrivalTime == 0) return false;
		else return System.currentTimeMillis() - prevMasterArrivalTime < Constants.CONFIG_DROP_TIME_WHEN_BOOT;
	}

	private static boolean scaleOutFrontTierDetect(int QLen, int frontTierCounter) {
		return System.currentTimeMillis() - prevScaleOutFrontTimeInMS > Constants.CONFIG_COOL_DOWN_FRONT &&
				QLen > Constants.CONFIG_FRONT_SCALE_OUT *  frontTierCounter;
	}

	private static void scaleOut(int tierNum) {
		System.err.println("===Scale out the tier: " + tierNum + "===");
		int vmId = SL.startVM();
		VM2TierMap.put(vmId, tierNum);
		if (tierNum == Constants.FRONT_TIER) {
			frontTierCounter++;
		} else if (tierNum == Constants.MID_TIER) {
			midTierCounter++;
		}
	}

	private static void bindServer(String ip, int port, int vmId) {
		try {
			Server server = new Server();
			Naming.bind("//" + ip + ":" +
					port +
					"/" + vmId, server);
//			System.err.println(vmId +
//					" Starts at port: " + port);
		} catch (RemoteException | MalformedURLException | AlreadyBoundException e) {
//			System.err.println("Failed to bind the server at port: " +
//					port);
//			e.printStackTrace();
		}

	}

	@Override
	public synchronized boolean removeVMFromMap(int vmId) throws RemoteException {
		if (VM2TierMap.containsKey(vmId)) {
			int targetTier = VM2TierMap.remove(vmId);
			if (targetTier == Constants.FRONT_TIER) {
				frontTierCounter--;
			} else if (targetTier == Constants.MID_TIER) {
				midTierCounter--;
			}
//			System.err.println("vmId: " + vmId + " tier: " + targetTier + " removed! ");
			return true;
		}
		return false;
	}

	@Override
	public int getTierNum(int vmId) throws RemoteException {
		return VM2TierMap.getOrDefault(vmId, -1);
	}

	@Override
	public void addRequestToMasterQ(Cloud.FrontEndOps.Request request) throws RemoteException {
		masterQ.add(request);
	}

	@Override
	public Cloud.FrontEndOps.Request pollRequestFromMasterQ() throws RemoteException {
		return masterQ.poll();
	}

	@Override
	public int getMasterQLength() throws RemoteException {
		return masterQ.size();
	}

	@Override
	public int getFrontTierCounter() throws RemoteException {
		return frontTierCounter;
	}

	@Override
	public int getMidTierCounter() throws RemoteException {
		return midTierCounter;
	}

	@Override
	public void coordinatorScaleOut(int targetTier) throws RemoteException {
		scaleOut(targetTier);
	}
}
