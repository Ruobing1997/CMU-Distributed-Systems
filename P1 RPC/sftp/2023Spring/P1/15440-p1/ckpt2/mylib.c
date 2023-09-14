#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <err.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>
#include <errno.h>

extern int errno;

#define MAXMSGLEN 100

int fd_global;

// implement close
// declare function pointer with the same prototype as the close function:
int (*orig_close)(int fd);
// insert client setup here:
int client_setup(char *msg) {
    char *serverip;
	char *serverport;
	char buf[MAXMSGLEN+1];
	unsigned short port;
	int sockfd, rv;
	struct sockaddr_in srv;
	
	// Get environment variable indicating the ip address of the server
	serverip = getenv("server15440");
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	port = (unsigned short)atoi(serverport);
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0) err(1, 0);			// in case of error
	
	// setup address structure to point to server
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port

	// actually connect to the server
	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) err(1,0);
	
	// send message to server
	int total_bytes;
	memcpy(&total_bytes, msg, sizeof(int));
	int num_left = total_bytes;
	int num_sent;
	int total_num_check = 0;
	const char *msg_cp = msg;
	while (num_left > 0) {
		num_sent = send(sockfd, msg_cp, num_left, 0);
		total_num_check += num_sent;
		if (num_sent < 0) {
			err(1, 0);
		}
		num_left -= num_sent;
		msg_cp += num_sent;
	}
	// fprintf(stderr, "client sending to server: %d %d %d\n", total_bytes, total_num_check, num_left);
	// get msg back;
	int ret_total_bytes;
	int cmd;
	int ret;
	while ((rv = recv(sockfd, buf, MAXMSGLEN, 0)) > 0) {
		memcpy(&ret_total_bytes, buf, sizeof(int));
		// extract cmd:
		memcpy(&cmd, buf + sizeof(int), sizeof(int));

		memcpy(&ret, buf + 2 * sizeof(int), sizeof(int));

		memcpy(&errno, buf + 3 * sizeof(int), sizeof(int));
		// fprintf(stderr, "ret_total_bytes, %d, cmd, %d, ret, %d, errno, %d\n", ret_total_bytes, cmd, ret, errno);
	}
	fprintf(stderr, "error checking: %d\n", errno);
	orig_close(sockfd);
	return ret;
}

// The following line declares a function pointer with the same prototype as the open function.  
int (*orig_open)(const char *pathname, int flags, ...);  // mode_t mode is needed when flags includes O_CREAT

char *marshall_open_args(const char *pathname, int flags, mode_t m) {
	// calculate total bytes:
	int total_bytes = sizeof(int) * 4 + strlen(pathname) + sizeof(mode_t);
	char* marshalled_data = (char*)malloc(total_bytes);

	// put total bytes into the marshalled data
	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	// put cmd as an int for saving bytes:
	int OPEN = 1;
	memcpy(marshalled_data + sizeof(int), (char*)&OPEN, sizeof(int));

	// put size of pathname into the marshalled data:
	int path_len = strlen(pathname);
	memcpy(marshalled_data + 2 * sizeof(int), (char*)&path_len, sizeof(int));

	// put pathname into the marshalled data:
	memcpy(marshalled_data + 3 * sizeof(int), pathname, strlen(pathname) + 1);

	// put flag into the data:
	char *flags_pointer = (char*)&flags;
	memcpy(marshalled_data + 3 * sizeof(int) + strlen(pathname) + 1, flags_pointer, sizeof(int));
	// fprintf(stderr, "flags pointer %s %d \n", flags_pointer, flags);
	// put mode_t into the data:
	char *m_pointer = (char*)&m;
	memcpy(marshalled_data + 4 * sizeof(int) + strlen(pathname) + 1, m_pointer, sizeof(mode_t));
	// fprintf(stderr, "m pointer %s %d \n", m_pointer, m);

	return marshalled_data;
}

// This is our replacement for the open function from libc.
int open(const char *pathname, int flags, ...) {
	mode_t m=0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}
	
	char *marshalled_data = marshall_open_args(pathname, flags, m);
    int fd = client_setup(marshalled_data);
	fd_global = fd;
	fprintf(stderr, "open ret value: %d\n", fd);
	return fd;
}

// replacement for close function from libc
int close(int fd) {
	// calculate total bytes:
	int total_bytes = sizeof(int) * 3;
	char* marshalled_data = (char*) malloc(total_bytes);

	// put total bytes into the marshalled data
	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	// put command into the marshalled data:
	int CLOSE = 2;
	memcpy(marshalled_data + sizeof(int), (char*)&CLOSE, sizeof(int));

	// put fd into the marshalled data
	char *fd_pointer = (char*)&fd_global;
	memcpy(marshalled_data + 2 * sizeof(int), fd_pointer, sizeof(int));

	int ret = client_setup(marshalled_data);
	free(marshalled_data);
	fprintf(stderr, "close ret value: %d\n", ret);
    return ret;
}

// implement read
// declare function pointer with the same prototype as the read function:
ssize_t (*orig_read)(int fd, void *buf, size_t count);

// replacement for read function from libc
ssize_t read(int fd, void *buf, size_t count) {
    client_setup("read");
    return orig_read(fd, buf, count);
}

// implement write
// declare function pointer with the same prototype as the write function:
ssize_t (*orig_write)(int fd, const void *buf, size_t count);

// replacement for write function from libc
ssize_t write(int fd, const void *buf, size_t count) {
	// calculate total bytes:
	int buf_size = count;
	int total_bytes = sizeof(int) * 4 + sizeof(count) + buf_size;
	char* marshalled_data = (char*) malloc(total_bytes);
	// put total bytes into the marshalled data
	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	// put command into the marshalled data:
	int WRITE = 3;
	memcpy(marshalled_data + sizeof(int), (char*)&WRITE, sizeof(int));

	// put fd into the marshalled data:
	char *fd_pointer = (char*)&fd_global;
	memcpy(marshalled_data + 2 * sizeof(int), fd_pointer, sizeof(int));

	// put buf length into the marshalled data:
	char *buf_len_pointer = (char*)&buf_size;
	memcpy(marshalled_data + 3 * sizeof(int), buf_len_pointer, sizeof(int));

	// put buf content into the marshalled data:
	memcpy(marshalled_data + 4 * sizeof(int), buf, buf_size);
	// put count into the marhsalled data:
	memcpy(marshalled_data + 4 * sizeof(int) + buf_size, (char*)&count, sizeof(size_t));
    // for (int i = 0; i < total_bytes; i++) {
	// 	fprintf(stderr, " %x ", marshalled_data[i]);
	// }
	// fprintf(stderr, "\n");

	int ret = client_setup(marshalled_data);
	free(marshalled_data);
	fprintf(stderr, "write ret value: %d\n", ret);
    return ret;
}

// implement lseek
// declare function pointer with the same prototype as the lseek function:
off_t (*orig_lseek)(int fd, off_t offset, int whence);

// replacement for lseek function from libc
off_t lseek(int fd, off_t offset, int whence) {
	client_setup("lseek");
	return orig_lseek(fd, offset, whence);
}

// implement stat
// declare function pointer with the same prototype as the stat function:
int (*orig_stat)(const char *pathname, struct stat *statbuf);

// replacement for stat function from libc
int stat(const char *pathname, struct stat *statbuf) {
	client_setup("stat");
	return orig_stat(pathname, statbuf);
}

// implement unlink
// declare function pointer with the same prototype as the unlink function:
int (*orig_unlink)(const char *pathname);

// replacement for unlink function from libc
int unlink(const char *pathname) {
	client_setup("unlink");
	return orig_unlink(pathname);
}

// implement getdirtree
// declare function pointer with the same prototype as the getdirtree function:
struct dirtreenode* (*orig_getdirtree)(char *path);

// replacement for getdirtree function from libc
struct dirtreenode* getdirtree(char *path) {
	client_setup("getdirtree");
	return orig_getdirtree(path);
}
// implement freedirtree
// declare function pointer with the same prototype as the freedirtree function:
void (*orig_freedirtree) (struct dirtreenode* dt);

// replacement for freedirtree function from libc
void freedirtree(struct dirtreenode* dt) {
	client_setup("freedirtree");
	return orig_freedirtree(dt);
}

// implement getdirentries
// declare function pointer with the same prototype as the getdirentries function:
ssize_t (*orig_getdirentries) (int fd, char *restrict buf,
size_t nbytes, off_t *restrict basep);

// replacement for freedirtree function from libc
ssize_t getdirentries(int fd, char *restrict buf,
size_t nbytes, off_t *restrict basep) {
	client_setup("getdirentries");
	return orig_getdirentries(fd, buf, nbytes, basep);
}


// This function is automatically called when program is started
void _init(void) {
	// set function pointer orig_open to point to the original open function
	orig_open = dlsym(RTLD_NEXT, "open");

    // set function pointer orig_close to point to the original close function
	orig_close = dlsym(RTLD_NEXT, "close");

    // set function pointer orig_read to point to the original read function
	orig_read = dlsym(RTLD_NEXT, "read");

	// set function pointer orig_write to point to the original write function
	orig_write = dlsym(RTLD_NEXT, "write");

	// set function pointer orig_lseek to point to the original lseek function
	orig_lseek = dlsym(RTLD_NEXT, "lseek");

    // set function pointer orig_stat to point to the original stat function
	orig_stat = dlsym(RTLD_NEXT, "stat");

	// set function pointer orig_unlink to point to the original unlink function
	orig_unlink = dlsym(RTLD_NEXT, "unlink");

	// set function pointer orig_getdirtree to point to the original getdirtree function
	orig_getdirtree = dlsym(RTLD_NEXT, "getdirtree");

	// set function pointer orig_freedirtree to point to the original freedirtree function
	orig_freedirtree = dlsym(RTLD_NEXT, "freedirtree");

	// set function pointer orig_getdirentries to point to the original getdirentries function
	orig_getdirentries = dlsym(RTLD_NEXT, "getdirentries");

	fprintf(stderr, "Init mylib\n");
}
