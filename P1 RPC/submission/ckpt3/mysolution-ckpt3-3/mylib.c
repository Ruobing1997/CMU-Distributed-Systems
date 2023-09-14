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
#include "../include/dirtree.h"

extern int errno;

#define MAXMSGLEN 100
#define OPEN 1
#define CLOSE 2
#define WRITE 3
#define READ 4
#define LSEEK 5
#define STAT 6
#define UNLINK 7
#define GETDIRTREE 8
#define GETDIRENTRIES 9
#define OFFSET 1000

int sockfd;

struct return_buffer {
	int ret_total_bytes;
	int cmd;
	int single_ret;
	int buffer_size;
	char* buffer;
	off_t basep;
	struct stat* statbuf;
};

// implement close
// declare function pointer with the same prototype as the close function:
int (*orig_close)(int fd);

struct dirtreenode* deserialize_preorder(char *buf, int size, int *offset) {
	// fprintf(stderr, "offset %d size: %d\n", *offset, size);
	if (*offset >= size) {
		return NULL;
	}

	int name_length = 0;
	memcpy(&name_length, buf + *offset, sizeof(int));
	// fprintf(stderr, "name len: %d\n", name_length);
	*offset += sizeof(int);

	if (name_length == 0) {
		return NULL;
	}

	char *name = malloc(name_length + 1);
	memcpy(name, buf + *offset, name_length);
	*offset += name_length;
	name[name_length] = '\0';

	int num_subdirs = 0;
	memcpy(&num_subdirs, buf + *offset, sizeof(int));
	*offset += sizeof(int);

	struct dirtreenode *node = malloc(sizeof(struct dirtreenode));
	node->name = name;
	node->num_subdirs = num_subdirs;

	node->subdirs = malloc(num_subdirs * sizeof(struct dirtreenode*));
	for (int i = 0; i < num_subdirs; i++) {
		node->subdirs[i] = deserialize_preorder(buf, size, offset);
	}
	// fprintf(stderr, "OUT: offset %d size: %d\n", *offset, size);
	return node;
}


struct dirtreenode *deserialize(char *buffer, int size) {
	int offset = 0;
	struct dirtreenode *root = deserialize_preorder(buffer, size, &offset);
	return root;
}

void build_connection() {
	char *serverip;
	char *serverport;
	unsigned short port;
	int rv;
	struct sockaddr_in srv;
	// Get environment variable indicating the ip address of the server
	serverip = getenv("server15440");
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	port = (unsigned short)atoi(serverport);
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	// fprintf(stderr, "sockfd in client? %d\n", sockfd);
	if (sockfd<0) err(1, 0);			// in case of error
	
	// setup address structure to point to server
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port

	// actually connect to the server
	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) err(1,0);
}

void close_connection() {
	orig_close(sockfd);
}

// insert client setup here:
struct return_buffer* client_setup(char *msg) {
	char buf[MAXMSGLEN+1];
	int rv;
	// send message to server
	int total_bytes;
	memcpy(&total_bytes, msg, sizeof(int));
	int num_left = total_bytes;
	// fprintf(stderr, "start total bytes num_left %d\n", num_left);
	int num_sent;
	const char *msg_cp = msg;
	while (num_left > 0) {
		num_sent = send(sockfd, msg_cp, num_left, 0);
		if (num_sent < 0) {
			err(1, 0);
		}
		num_left -= num_sent;
		msg_cp += num_sent;
	}
	// fprintf(stderr, "client sending to server: %d %d %d\n", total_bytes, total_num_check, num_left);
	// get msg back;
	struct return_buffer *ret_buf = malloc(sizeof(struct return_buffer));
	rv = recv(sockfd, buf, sizeof(int), 0);
	memcpy(&(ret_buf->ret_total_bytes), buf, sizeof(int));
	int received_bytes = rv;
	char* recv_buf = malloc(ret_buf->ret_total_bytes);
	// fprintf(stderr, "ret_total_bytes : %d\n", ret_buf->ret_total_bytes);
	while (received_bytes < ret_buf->ret_total_bytes && 
	(rv = recv(sockfd, recv_buf, ret_buf->ret_total_bytes, 0)) > 0) {
		received_bytes += rv;
		// fprintf(stderr, "received bytes: %d, rv: %d, total: %d\n", received_bytes, rv, ret_buf->ret_total_bytes);
		// extract cmd:
		memcpy(&(ret_buf->cmd), recv_buf, sizeof(int));
		// fprintf(stderr, "cmd %d\n", ret_buf->cmd);

		if (ret_buf->cmd == READ) {
			memcpy(&(ret_buf->single_ret), recv_buf + sizeof(int), sizeof(int));
			// fprintf(stderr, "retbuf buffer single: %d\n", ret_buf->single_ret);
			memcpy(&(ret_buf->buffer_size), recv_buf + 2 * sizeof(int), sizeof(int));
			// fprintf(stderr, "retbuf buffer size: %d\n", ret_buf->buffer_size);
			ret_buf->buffer = malloc(ret_buf->buffer_size);
			memcpy(ret_buf->buffer, recv_buf + 3 * sizeof(int), ret_buf->buffer_size);
			// fprintf(stderr, "retbuf buffer: %s\n", ret_buf->buffer);
			memcpy(&errno, recv_buf + 3 * sizeof(int) + ret_buf->buffer_size, sizeof(int));
		} else if (ret_buf->cmd == STAT) {
			memcpy(&(ret_buf->single_ret), recv_buf + sizeof(int), sizeof(int));
			ret_buf->statbuf = malloc(sizeof(struct stat));
			memcpy(ret_buf->statbuf, recv_buf + 2 * sizeof(int), sizeof(struct stat));
		} else if (ret_buf->cmd == GETDIRTREE) {
			memcpy(&(ret_buf->buffer_size), recv_buf + sizeof(int), sizeof(int));
			// fprintf(stderr, "retbuf buffer size: %d\n", ret_buf->buffer_size);
			ret_buf->buffer = malloc(ret_buf->buffer_size);
			memcpy(ret_buf->buffer, recv_buf + 2 * sizeof(int), ret_buf->buffer_size);
			memcpy(&errno, recv_buf + 2 * sizeof(int) + ret_buf->buffer_size, sizeof(int));
		} else if (ret_buf->cmd == GETDIRENTRIES) { 
			memcpy(&(ret_buf->single_ret), recv_buf + sizeof(int), sizeof(size_t));
			// fprintf(stderr, "retbuf buffer single: %d\n", ret_buf->single_ret);
			memcpy(&(ret_buf->buffer_size), recv_buf + sizeof(int) + sizeof(size_t), sizeof(size_t));
			// fprintf(stderr, "retbuf buffer size: %d\n", ret_buf->buffer_size);
			memcpy(&(ret_buf->basep), recv_buf + sizeof(int) + 2 * sizeof(size_t), sizeof(off_t));
			// fprintf(stderr, "retbuf basep: %lu\n", ret_buf->basep);
			ret_buf->buffer = malloc(ret_buf->buffer_size);
			memcpy(ret_buf->buffer, recv_buf + sizeof(int) + 2 * sizeof(size_t) + sizeof(off_t), ret_buf->buffer_size);
			// for (int i = 0; i < ret_buf->buffer_size; i++) {
			// 	fprintf(stderr, "%c", ret_buf->buffer);
			// }
			memcpy(&errno, recv_buf + sizeof(int) + 2 * sizeof(size_t) + sizeof(off_t) + ret_buf->buffer_size, sizeof(int));
		} else {
			memcpy(&(ret_buf->single_ret), recv_buf + sizeof(int), sizeof(int));
			memcpy(&errno, recv_buf + 2 * sizeof(int), sizeof(int));
		}
	}
	free(recv_buf);
	// fprintf(stderr, "error checking: %d\n", errno);
	return ret_buf;
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
	int open_cmd = OPEN;
	memcpy(marshalled_data + sizeof(int), (char*)&open_cmd, sizeof(int));

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
	fprintf(stderr, "*** In Open ***");
	mode_t m=0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}
	
	char *marshalled_data = marshall_open_args(pathname, flags, m);
    struct return_buffer* ret = client_setup(marshalled_data);
	int fd = ret->single_ret;
	fprintf(stderr, "open ret value: %d\n", fd);
	free(marshalled_data);
	free(ret);
	return fd;
}

// replacement for close function from libc
int close(int fd) {
	fprintf(stderr, "*** In Close ***");
	if (fd < OFFSET) {
		return orig_close(fd);
	}
	// calculate total bytes:
	int total_bytes = sizeof(int) * 3;
	char* marshalled_data = (char*) malloc(total_bytes);

	// put total bytes into the marshalled data
	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	// put command into the marshalled data:
	int close_cmd = CLOSE;
	memcpy(marshalled_data + sizeof(int), (char*)&close_cmd, sizeof(int));

	// put fd into the marshalled data
	char *fd_pointer = (char*)&fd;
	memcpy(marshalled_data + 2 * sizeof(int), fd_pointer, sizeof(int));

	struct return_buffer* ret = client_setup(marshalled_data);
	int close_ret = ret->single_ret;
	free(marshalled_data);
	fprintf(stderr, "close ret value: %d\n", close_ret);
	free(ret);
    return close_ret;
}

// implement read
// declare function pointer with the same prototype as the read function:
ssize_t (*orig_read)(int fd, void *buf, size_t count);

// replacement for read function from libc
ssize_t read(int fd, void *buf, size_t count) {
	fprintf(stderr, "*** In Read ***");
	if (fd < OFFSET) {
		return orig_read(fd, buf, count);
	}
	// fprintf(stderr, "fd global %d\n", fd_global);
	int buf_size = count;
	int total_bytes = sizeof(int) * 4 + sizeof(count) + buf_size;
	char* marshalled_data = (char*) malloc(total_bytes);
	// put total bytes into the marshalled data
	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	// put command into the marshalled data:
	int read_cmd = READ;
	memcpy(marshalled_data + sizeof(int), (char*)&read_cmd, sizeof(int));

	// put fd into the marshalled data:
	char *fd_pointer = (char*)&fd;
	memcpy(marshalled_data + 2 * sizeof(int), fd_pointer, sizeof(int));

	// put buf length into the marshalled data:
	char *buf_len_pointer = (char*)&buf_size;
	memcpy(marshalled_data + 3 * sizeof(int), buf_len_pointer, sizeof(int));

	// put buf content into the marshalled data:
	memcpy(marshalled_data + 4 * sizeof(int), buf, buf_size);
	// put count into the marhsalled data:
	memcpy(marshalled_data + 4 * sizeof(int) + buf_size, (char*)&count, sizeof(size_t));

	// fprintf(stderr, "read fd: %d\n", fd_global);

	struct return_buffer* ret = client_setup(marshalled_data);
	int read_ret = ret->single_ret;
	char* read_ret_buffer = ret->buffer;
	int size_buffer;
	if (count < read_ret) {
		size_buffer = count;
	} else {
		size_buffer = read_ret;
	}
	memcpy(buf, read_ret_buffer, size_buffer);
	// for (int i = 0; i < size_buffer; i++) {
	// 	fprintf(stderr, " %c ", ((char*)buf)[i]);
	// }
	free(marshalled_data);
	free(ret->buffer);
	free(ret);
	fprintf(stderr, "read ret value: %d\n", read_ret);
    return read_ret;
}

// implement write
// declare function pointer with the same prototype as the write function:
ssize_t (*orig_write)(int fd, const void *buf, size_t count);

// replacement for write function from libc
ssize_t write(int fd, const void *buf, size_t count) {
	fprintf(stderr, "*** In Write ***");
	if (fd < OFFSET) {
		return orig_write(fd, buf, count);
	}
	// calculate total bytes:
	int buf_size = count;
	int total_bytes = sizeof(int) * 4 + sizeof(count) + buf_size;
	char* marshalled_data = (char*) malloc(total_bytes);
	// put total bytes into the marshalled data
	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	// put command into the marshalled data:
	int write_cmd = WRITE;
	memcpy(marshalled_data + sizeof(int), (char*)&write_cmd, sizeof(int));

	// put fd into the marshalled data:
	char *fd_pointer = (char*)&fd;
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

	struct return_buffer* ret = client_setup(marshalled_data);
	int write_ret = ret->single_ret;
	free(marshalled_data);
	free(ret);
	fprintf(stderr, "write ret value: %d\n", write_ret);
    return write_ret;
}

// implement lseek
// declare function pointer with the same prototype as the lseek function:
off_t (*orig_lseek)(int fd, off_t offset, int whence);

// replacement for lseek function from libc
off_t lseek(int fd, off_t offset, int whence) {
	fprintf(stderr, "*** In lseek ***");
	if (fd < OFFSET) {
		return orig_lseek(fd, offset, whence);
	}
	int total_bytes = 4 * sizeof(int) + sizeof(off_t);
	char* marshalled_data = (char*) malloc(total_bytes);
	
	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	int lseek_cmd = LSEEK;
	memcpy(marshalled_data + sizeof(int), (char*)&lseek_cmd, sizeof(int));

	// put fd into the marshalled data:
	char *fd_pointer = (char*)&fd;
	memcpy(marshalled_data + 2 * sizeof(int), fd_pointer, sizeof(int));

	// put offset into the marshalled data:
	char *off_set_pointer = (char*)&offset;
	memcpy(marshalled_data + 3 * sizeof(int), off_set_pointer, sizeof(off_t));

	// put whence into the marshalled data:
	memcpy(marshalled_data + 3 * sizeof(int) + sizeof(off_t), (char*)&whence, sizeof(int));

	struct return_buffer* ret = client_setup(marshalled_data);
	int lseek_ret = ret->single_ret;
	free(marshalled_data);
	free(ret);
	fprintf(stderr, "lseek ret value: %d\n", lseek_ret);
	return lseek_ret;
}

// implement stat
// declare function pointer with the same prototype as the stat function:
int (*orig_stat)(const char *pathname, struct stat *statbuf);

// replacement for stat function from libc
int stat(const char *pathname, struct stat *statbuf) {
	fprintf(stderr, "*** In stat ***");
	int total_bytes = sizeof(int) * 3 + strlen(pathname) + sizeof(struct stat);
	char* marshalled_data = (char*)malloc(total_bytes);

	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	int stat_cmd = STAT;
	memcpy(marshalled_data + sizeof(int), (char*)&stat_cmd, sizeof(int));

	// put size of pathname into the marshalled data:
	int path_len = strlen(pathname);
	memcpy(marshalled_data + 2 * sizeof(int), (char*)&path_len, sizeof(int));

	// put pathname into the marshalled data:
	memcpy(marshalled_data + 3 * sizeof(int), pathname, strlen(pathname) + 1);

	// put struct into the marshalled data:
	memcpy(marshalled_data + 3 * sizeof(int) + strlen(pathname) + 1, statbuf, sizeof(struct stat));
	
	struct return_buffer* ret = client_setup(marshalled_data);
	int stat_ret = ret->single_ret;

	memcpy(statbuf, ret->statbuf, sizeof(struct stat));

	free(marshalled_data);
	free(ret->statbuf);
	free(ret);
	fprintf(stderr, "stat ret value: %d\n", stat_ret);
	return stat_ret;
}

// implement unlink
// declare function pointer with the same prototype as the unlink function:
int (*orig_unlink)(const char *pathname);

// replacement for unlink function from libc
int unlink(const char *pathname) {
	fprintf(stderr, "*** In unlink ***");
	int total_bytes = sizeof(int) * 3 + strlen(pathname);
	char* marshalled_data = (char*)malloc(total_bytes);

	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	int unlink_cmd = UNLINK;
	memcpy(marshalled_data + sizeof(int), (char*)&unlink_cmd, sizeof(int));

	// put size of pathname into the marshalled data:
	int path_len = strlen(pathname);
	memcpy(marshalled_data + 2 * sizeof(int), (char*)&path_len, sizeof(int));

	// put pathname into the marshalled data:
	memcpy(marshalled_data + 3 * sizeof(int), pathname, strlen(pathname) + 1);

	struct return_buffer* ret = client_setup(marshalled_data);
	int unlink_ret = ret->single_ret;
	fprintf(stderr, "unlink ret value: %d\n", unlink_ret);
	free(marshalled_data);
	free(ret);
	return unlink_ret;
}

void my_free_dirtree(struct dirtreenode *node) {
  if (node == NULL) return;
  for (int i = 0; i < node->num_subdirs; i++) {
    my_free_dirtree(node->subdirs[i]);
  }
  free(node->subdirs);
  free(node->name);
  free(node);
}

// implement getdirtree
// declare function pointer with the same prototype as the getdirtree function:
struct dirtreenode* (*orig_getdirtree)(char *path);

// replacement for getdirtree function from libc
struct dirtreenode* getdirtree(const char *path) {
	fprintf(stderr, "**call get dir tree**\n");
	// total bytes + cmd + pathlen + path
	// int + int + int + strlen(path)
	int total_bytes = sizeof(int) * 3 + strlen(path);
	char* marshalled_data = (char*)malloc(total_bytes);

	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	int getdirtree_cmd = GETDIRTREE;
	memcpy(marshalled_data + sizeof(int), (char*)&getdirtree_cmd, sizeof(int));

	int path_len = strlen(path);
	memcpy(marshalled_data + 2 * sizeof(int), (char*)&path_len, sizeof(int));

	// put pathname into the marshalled data:
	memcpy(marshalled_data + 3 * sizeof(int), path, strlen(path) + 1);

	struct return_buffer* ret = client_setup(marshalled_data);
	char* serialized_tree = malloc(ret->buffer_size);
	memcpy(serialized_tree, ret->buffer, ret->buffer_size);
	struct dirtreenode* root = deserialize(serialized_tree, ret->buffer_size);
	free(marshalled_data);
	free(serialized_tree);
	free(ret->buffer);
	free(ret);
	// fprintf(stderr, "getdirtree ret value: %s\n", root->name);
	return root;
}
// implement freedirtree
// declare function pointer with the same prototype as the freedirtree function:
// void (*orig_freedirtree) (struct dirtreenode* dt);

// replacement for freedirtree function from libc
void freedirtree(struct dirtreenode* dt) {
	my_free_dirtree(dt);
}

ssize_t (*orig_getdirentries) (int fd, char *restrict buf,
size_t nbytes, off_t *restrict basep);

ssize_t getdirentries(int fd, char *restrict buf,
size_t nbytes, off_t *restrict basep) {
	if (fd < OFFSET) {
		return orig_getdirentries(fd, buf, nbytes, basep);
	}
	fprintf(stderr, "*** In getdirentries ***");
	// fprintf(stderr, "**call get entries **\n");
	int total_bytes = 3 * sizeof(int) + sizeof(size_t) + sizeof(off_t);
	char* marshalled_data = (char*)malloc(total_bytes);

	char *total_bytes_pointer = (char*)&total_bytes;
	memcpy(marshalled_data, total_bytes_pointer, sizeof(int));

	int getdirentries_cmd = GETDIRENTRIES;
	memcpy(marshalled_data + sizeof(int), (char*)&getdirentries_cmd, sizeof(int));

	memcpy(marshalled_data + 2 * sizeof(int), (char*)&fd, sizeof(int));

	memcpy(marshalled_data + 3 * sizeof(int), (char*)&nbytes, sizeof(size_t));
	
	memcpy(marshalled_data + 3 * sizeof(int) + sizeof(size_t), basep, sizeof(off_t));
	// fprintf(stderr, "basep: %ld\n", *basep);

	struct return_buffer* ret = client_setup(marshalled_data);
	// fprintf(stderr, "getdirentries ret value: %d\n", ret->single_ret);

	int getdir_ret = ret->single_ret;
	off_t ret_basep = ret->basep;
	// fprintf(stderr, "ret_basep basep: %ld\n", ret_basep);
	
	*basep = ret_basep;
	// char* getdir_ret_buffer = ret->buffer;
	// fprintf(stderr, "updated basep: %ld\n", *basep);
	int size_buffer;
	if (getdir_ret < nbytes) {
		size_buffer = getdir_ret;
	} else {
		size_buffer = nbytes;
	}
	memcpy(buf, ret->buffer, size_buffer);
	// for (int i = 0; i < nbytes; i++) {
	// 	fprintf(stderr, "%c ", ((char *) buf)[i]);
	// }
	free(marshalled_data);
	free(ret->buffer);
	free(ret);
	// fprintf(stderr, "getdirentries ret value: %s\n", ret->buffer);
	fprintf(stderr, "getdirentries ret value: %d\n", getdir_ret);
	return getdir_ret;
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
	// orig_freedirtree = dlsym(RTLD_NEXT, "freedirtree");

	// set function pointer orig_getdirentries to point to the original getdirentries function
	orig_getdirentries = dlsym(RTLD_NEXT, "getdirentries");

	// fprintf(stderr, "Init mylib\n");
	build_connection();
}

void _fini(void) {
	close_connection();
}
