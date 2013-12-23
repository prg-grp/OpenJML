package tests;

import java.util.Collection;

import junit.framework.Assert;

import org.jmlspecs.openjml.JmlTree;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class escTrace extends EscBase {
    
    public escTrace(String option, String solver) {
        super(option,solver);
    }
    
    @Override
    public void setUp() throws Exception {
        captureOutput = true;
        super.setUp();
        main.addOptions("-subexpressions");
    }
    
    /** This String declaration and assignment */
    @Test
    public void testSimpleTrace() {
        String expectedOut =""
+JmlTree.eol+"TRACE of tt.TestJava.m1(int)"
+JmlTree.eol+" \t//Assume axioms"
+JmlTree.eol+" \t//Assume static final constant fields"
+JmlTree.eol+" \t//Assume field type, allocation, and nullness"
+JmlTree.eol+" \t//Assume parameter type, allocation, and nullness"
+JmlTree.eol+" \t//Declare pre-state value of formals"
+JmlTree.eol+" \t//Assume invariants for java.lang.Object"
+JmlTree.eol+" \t//Assume invariants for tt.TestJava"
+JmlTree.eol+" \t//Assume Preconditions"
+JmlTree.eol+"/tt/TestJava.java:3:  \tAssumeCheck assertion: __JML_AssumeCheck_ != 1"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_\t === 0"
+JmlTree.eol+"\t\t\tVALUE: 1\t === 1"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_ != 1\t === true"
+JmlTree.eol+" \t//try..."
+JmlTree.eol+" \t//End of pre-state"
+JmlTree.eol+" \t//Method Body"
+JmlTree.eol+"/tt/TestJava.java:4:  \tint j = 5"
+JmlTree.eol+"\t\t\tVALUE: 5\t === 5"
+JmlTree.eol+"\t\t\tVALUE: j\t === 5"
+JmlTree.eol+"/tt/TestJava.java:5:  \tj = j + i"
+JmlTree.eol+"\t\t\tVALUE: j\t === 5"
+JmlTree.eol+"\t\t\tVALUE: i\t === 2"
+JmlTree.eol+"\t\t\tVALUE: j + i\t === 7"
+JmlTree.eol+"\t\t\tVALUE: j = j + i\t === 7"
+JmlTree.eol+"/tt/TestJava.java:6:  \tassert Assert j != 7;"
+JmlTree.eol+"\t\t\tVALUE: j\t === 7"
+JmlTree.eol+"\t\t\tVALUE: 7\t === 7"
+JmlTree.eol+"\t\t\tVALUE: j != 7\t === false"
+JmlTree.eol+"/tt/TestJava.java:6:  \tAssumeCheck assertion: __JML_AssumeCheck_ != 2"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_\t === 0"
+JmlTree.eol+"\t\t\tVALUE: 2\t === 2"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_ != 2\t === true"
+JmlTree.eol+"/tt/TestJava.java:6:  Invalid assertion (Assert)"
+JmlTree.eol
+JmlTree.eol;

        main.addOptions("-method=m1");
        helpTCX("tt.TestJava","package tt; \n"
                +"public class TestJava { \n"
                
                +"  public void m1(int i) {\n"
                +"       int j = 5;\n"
                +"       j = j + i;\n"
                +"       //@ assert j != 7;\n"
                +"  }\n"
                +"}"
                ,"/tt/TestJava.java:6: warning: The prover cannot establish an assertion (Assert) in method m1",12
                );
        String output = output();
        String error = errorOutput();
        Assert.assertEquals("",error);
        Assert.assertEquals(expectedOut,output);
    }

    /** This String declaration and assignment */
    @Test
    public void testFieldTrace() {
        String expectedOut =""
+JmlTree.eol+"TRACE of tt.TestJava.m1(int)"
+JmlTree.eol+" \t//Assume axioms"
+JmlTree.eol+" \t//Assume static final constant fields"
+JmlTree.eol+" \t//Assume field type, allocation, and nullness"
+JmlTree.eol+" \t//Assume parameter type, allocation, and nullness"
+JmlTree.eol+" \t//Declare pre-state value of formals"
+JmlTree.eol+" \t//Assume invariants for java.lang.Object"
+JmlTree.eol+" \t//Assume invariants for tt.TestJava"
+JmlTree.eol+" \t//Assume Preconditions"
+JmlTree.eol+"/tt/TestJava.java:4:  \tAssumeCheck assertion: __JML_AssumeCheck_ != 1"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_\t === 0"
+JmlTree.eol+"\t\t\tVALUE: 1\t === 1"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_ != 1\t === true"
+JmlTree.eol+" \t//try..."
+JmlTree.eol+" \t//End of pre-state"
+JmlTree.eol+" \t//Method Body"
+JmlTree.eol+"/tt/TestJava.java:5:  \tk = 5 + i"
+JmlTree.eol+"\t\t\tVALUE: 5\t === 5"
+JmlTree.eol+"\t\t\tVALUE: i\t === 2"
+JmlTree.eol+"\t\t\tVALUE: 5 + i\t === 7"
+JmlTree.eol+"\t\t\tVALUE: k = 5 + i\t === 7"
+JmlTree.eol+"/tt/TestJava.java:6:  \tassert Assert k != 7;"
+JmlTree.eol+"\t\t\tVALUE: k\t === 7"
+JmlTree.eol+"\t\t\tVALUE: 7\t === 7"
+JmlTree.eol+"\t\t\tVALUE: k != 7\t === false"
+JmlTree.eol+"/tt/TestJava.java:6:  \tAssumeCheck assertion: __JML_AssumeCheck_ != 2"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_\t === 0"
+JmlTree.eol+"\t\t\tVALUE: 2\t === 2"
+JmlTree.eol+"\t\t\tVALUE: __JML_AssumeCheck_ != 2\t === true"
+JmlTree.eol+"/tt/TestJava.java:6:  Invalid assertion (Assert)"
+JmlTree.eol
+JmlTree.eol;
        main.addOptions("-method=m1");
        helpTCX("tt.TestJava","package tt; \n"
                +"public class TestJava { \n"
                +"       int k;\n"
                
                +"  public void m1(int i) {\n"
                +"       k = 5 + i;\n"
                +"       //@ assert k != 7;\n"
                +"  }\n"
                
                
                +"}"
                ,"/tt/TestJava.java:6: warning: The prover cannot establish an assertion (Assert) in method m1",12
                );
        String output = output();
        String error = errorOutput();
        Assert.assertEquals("",error);
        Assert.assertEquals(expectedOut,output);
    }

}