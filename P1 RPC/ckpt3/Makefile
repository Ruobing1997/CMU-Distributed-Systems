all: mylib.so server

mylib.o: mylib.c
	gcc -Wall -fPIC -DPIC -c mylib.c

mylib.so: mylib.o
	ld -shared -o mylib.so mylib.o -ldl -L../lib -ldirtree

server: 
	gcc server.c -o server -Wall -I../include -L../lib -ldirtree

clean:
	rm -f *.o *.so server

