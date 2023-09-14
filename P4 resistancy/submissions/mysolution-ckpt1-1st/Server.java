/* Skeleton code for Server */

public class Server implements ProjectLib.CommitServing {
	static ProjectLib PL;
	static MessageHandlingCallBack messageHandlingCallBack;
	public void startCommit( String filename, byte[] img, String[] sources ) {
		TwoPCLogic twoPCLogic = new TwoPCLogic(filename, img, sources);
		twoPCLogic.setPL(PL);
		twoPCLogic.setMessageHandlingCallBack(messageHandlingCallBack);
		twoPCLogic.startCommit();
	}
	
	public static void main ( String args[] ) throws Exception {
		if (args.length != 1) throw new Exception("Need 1 arg: <port>");
		Server srv = new Server();
		messageHandlingCallBack = new MessageHandlingCallBack();
		PL = new ProjectLib( Integer.parseInt(args[0]), srv, messageHandlingCallBack);
		// main loop
		while (true) {
			ProjectLib.Message msg = PL.getMessage();
			System.out.println( "Server: Got message from " + msg.addr );
		}
	}
}

