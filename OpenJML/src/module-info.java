module openjml {
	requires jdk.compiler.ext;
	requires jdk.compiler;

	opens com.sun.tools.javac.code;
	opens com.sun.source.tree;
	opens com.sun.tools.javac.comp;
}