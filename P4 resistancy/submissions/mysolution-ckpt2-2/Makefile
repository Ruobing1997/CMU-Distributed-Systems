all: AdvancedMessage.class AdvancedMessageSerializer.class \
Constants.class FileInfo.class MessageHandlingCallBack.class \
Server.class UserNode.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
