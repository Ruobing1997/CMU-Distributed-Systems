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

#define MAXMSGLEN 100
// implement close
// declare function pointer with the same prototype as the close function:
int (*orig_close)(int fd);
// insert client setup here:
int client_setup(char *msg) {
    char *serverip;
	char *serverport;
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
	fprintf(stderr, "client sending to server: %s\n", msg);
	send(sockfd, msg, strlen(msg), 0);	// send message; should check return value
    orig_close(sockfd);
	return 0;
}

// The following line declares a function pointer with the same prototype as the open function.  
int (*orig_open)(const char *pathname, int flags, ...);  // mode_t mode is needed when flags includes O_CREAT

// This is our replacement for the open function from libc.
int open(const char *pathname, int flags, ...) {
	mode_t m=0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}
    client_setup("open");
	return orig_open(pathname, flags, m);
}



// replacement for close function from libc
int close(int fd) {
    client_setup("close");
    return orig_close(fd);
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
    client_setup("write");
    return orig_write(fd, buf, count);
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
