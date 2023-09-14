all: AdvancedMessage.class AdvancedMessageSerializer.class \
Constants.class MessageHandlingCallBack.class \
Server.class TwoPCLogic.class UserNode.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
