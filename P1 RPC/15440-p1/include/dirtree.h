#ifndef __DIRTREE_H__
#define __DIRTREE_H__

// dirtree.h

// Directory tree data structure
// Each node has a pointer to a NULL terminated name string,
//   count of subdirectories, and a pointer to an array of
//   pointers indicating the dirtreenode structures for the 
//   subdirectories.  All of the node data structures, name 
//   strings, and pointer arrays are allocated on the heap.

struct dirtreenode {
	char *name;
	int num_subdirs;
	struct dirtreenode **subdirs;
};


// getdirtree
//    Input: Null terminated string indicating path
//    What it does:  Recusively descend through directory 
//       hierarchy starting at directory indicated by path string. 
//       Allocates and constructs directory tree data structure
//       representing all of the directories in the hierarchy.
//    Returns: pointer to root node of directory tree structure
//       or NULL if there was en error (will set errno in this case)

struct dirtreenode* getdirtree( const char *path );


// freedirtree
//    Input: pointer to directory tree structure created by getdirtree
//    What it does:  Recursively frees the memory used to hold 
//       the directory tree structures, including the name strings,
//       pointer arrays, and dirtreenode structures.
//    Returns: nothing

void freedirtree( struct dirtreenode* dt );

#endif

