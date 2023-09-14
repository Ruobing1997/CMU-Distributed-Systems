/* Sample skeleton for proxy */

import java.io.*;

class Proxy {
	
	private static class FileHandler implements FileHandling {

		public int open( String path, OpenOption o ) {
			return Errors.ENOSYS;
		}

		public int close( int fd ) {
			return Errors.ENOSYS;
		}

		public long write( int fd, byte[] buf ) {
			return Errors.ENOSYS;
		}

		public long read( int fd, byte[] buf ) {
			return Errors.ENOSYS;
		}

		public long lseek( int fd, long pos, LseekOption o ) {
			return Errors.ENOSYS;
		}

		public int unlink( String path ) {
			return Errors.ENOSYS;
		}

		public void clientdone() {
			return;
		}

	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
		System.out.println("Hello World");
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

