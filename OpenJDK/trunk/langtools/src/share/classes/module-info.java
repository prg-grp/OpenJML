module jdk.compiler.ext {
	requires java.logging;
	requires java.xml;
	requires java.base;


	exports com.sun.tools.javac.util;
	exports com.sun.tools.javac.tree;
	exports com.sun.tools.javac.parser;
}