#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include <errno.h>
#include <fcntl.h>

extern int errno;

#define MAXMSGLEN 100
#define OFFSET 1000
#define OPEN 1
#define CLOSE 2
#define WRITE 3

int main(int argc, char**argv) {
	char buf[MAXMSGLEN+1];
	char *serverport;
	unsigned short port;
	int sockfd, sessfd, rv;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short)atoi(serverport);
	else port=15440;
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0) err(1, 0);			// in case of error
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			// server port

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) err(1,0);
	
	// start listening for connections
	rv = listen(sockfd, 5);
	if (rv<0) err(1,0);
	
	while (1) {
		// wait for next client, get session socket
		sa_size = sizeof(struct sockaddr_in);
		sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
		if (sessfd<0) err(1,0);
		rv = recv(sessfd, buf, sizeof(int), 0);
		// get messages and send replies to this client, until it goes away
		// extract total bytes:
		int total_bytes;
		memcpy(&total_bytes, buf, sizeof(int));
		fprintf(stderr, "total_bytes %d\n", total_bytes);
		
		int received_bytes = rv;
		char* buf = malloc(total_bytes);
		while (received_bytes < total_bytes && 
		(rv = recv(sessfd, buf, total_bytes, 0)) > 0) {
			received_bytes += rv;

			// extract cmd:
			int cmd;
			memcpy(&cmd, buf, sizeof(int));
			fprintf(stderr, "cmd %d\n", cmd);

			if (cmd == OPEN) {
				// extract pathname len for getting pathname:
				int pathname_len;
				memcpy(&pathname_len, buf + sizeof(int), sizeof(int));
				// fprintf(stderr, "pathname_len: %d \n", pathname_len);

				int size = (pathname_len + 1) * sizeof(char);
				char *pathname = malloc(size);
				memcpy(pathname, buf + 2 * sizeof(int), size);
				// fprintf(stderr, "pathname: %s \n", pathname);

				int flags;
				memcpy(&flags, buf + 2 * sizeof(int) + size, sizeof(int));

				mode_t m;
				memcpy(&m, buf + 3 * sizeof(int) + size, sizeof(mode_t));
				// for (int i = 0; i < pathname_len; i++) {
				// 	fprintf(stderr, " %x ", pathname[i]);
				// }
				// fprintf(stderr, "\n");

				fprintf(stderr, "pathname_len: %d, pathname %s, flags %d, m %d \n", pathname_len, pathname, flags, m);

				int fd = open(pathname, flags, m);
				
				int fd_OFFSET = fd + OFFSET;

				fprintf(stderr, "file open descriptor in Open %d\n", fd);

				int total_bytes = 4 * sizeof(int);
				char* return_buf = malloc(total_bytes);
				memcpy(return_buf, (char*)&total_bytes, sizeof(int));

				// put cmd:
				memcpy(return_buf + sizeof(int), (char*)&cmd, sizeof(int));

				// put fd with offset:
				if (fd != -1) {
					fd = fd_OFFSET;
				}
				memcpy(return_buf + 2 * sizeof(int), (char*)&fd, sizeof(int));

				// put errno:
				memcpy(return_buf + 3 * sizeof(int), (char*)&errno, sizeof(int));

				send(sessfd, return_buf, total_bytes, 0);
				free(pathname);
				free(return_buf);
				free(buf);

			} else if (cmd == WRITE) {
				fprintf(stderr, "***In write*** \n");
				int fd;
				memcpy(&fd, buf + sizeof(int), sizeof(int));
				fd -= OFFSET;

				int write_buffer_len;
				memcpy(&write_buffer_len, buf + 2 * sizeof(int), sizeof(int));
				fprintf(stderr, "write_buffer_len: %d \n", write_buffer_len);

				char* write_buffer = malloc(write_buffer_len * sizeof(char));
				memcpy(write_buffer, buf + 3 * sizeof(int), write_buffer_len);

				size_t count;
				memcpy(&count, buf + 3 * sizeof(int) + write_buffer_len, sizeof(count));
				// fprintf(stderr, "fd: %d, write_buffer: %s, count: %ld \n", fd, write_buffer, count);
				fprintf(stderr, "fd: %d, count: %ld \n", fd, count);

				int ret = write(fd, write_buffer, count);
				
				int total_bytes = 4 * sizeof(int);
				char* return_buf = malloc(total_bytes);
				memcpy(return_buf, (char*)&total_bytes, sizeof(int));

				// put cmd:
				memcpy(return_buf + sizeof(int), (char*)&cmd, sizeof(int));

				// put return value with offset:
				memcpy(return_buf + 2 * sizeof(int), (char*)&ret, sizeof(int));

				// put errno:
				memcpy(return_buf + 3 * sizeof(int), (char*)&errno, sizeof(int));

				send(sessfd, return_buf, total_bytes, 0);
				
				free(write_buffer);
				free(return_buf);
				free(buf);
			} else if (cmd == CLOSE) {
				fprintf(stderr, "***In Close*** \n");
				int fd;
				memcpy(&fd, buf + sizeof(int), sizeof(int));
				fd -= OFFSET;
				fprintf(stderr, "Close fd: %d\n", fd);

				int ret = close(fd);

				int total_bytes = 4 * sizeof(int);
				char* return_buf = malloc(total_bytes);
				memcpy(return_buf, (char*)&total_bytes, sizeof(int));

				// put cmd:
				memcpy(return_buf + sizeof(int), (char*)&cmd, sizeof(int));

				// put return value with offset:
				memcpy(return_buf + 2 * sizeof(int), (char*)&ret, sizeof(int));

				// put errno:
				memcpy(return_buf + 3 * sizeof(int), (char*)&errno, sizeof(int));

				send(sessfd, return_buf, total_bytes, 0);
				free(return_buf);
				free(buf);

			} else {
				buf[rv]=0;		// null terminate string to print
				printf("%s\n", buf);

				int len = rv + 1;
				char ret_buf[len];
				ret_buf[0] = rv + '0';
				memcpy(ret_buf + 1, buf, rv + 1);
				ret_buf[len] = -1;
				send(sessfd, ret_buf, len, 0);
			}
		}
		
		// either client closed connection, or error
		if (rv<0) err(1,0);
		close(sessfd);
	}
	
	// close socket
	close(sockfd);

	return 0;
}

