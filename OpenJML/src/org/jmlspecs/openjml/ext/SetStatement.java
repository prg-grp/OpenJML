/*
 * This file is part of the OpenJML project. 
 * Author: David R. Cok
 */
package org.jmlspecs.openjml.ext;

import static org.jmlspecs.openjml.JmlTokenKind.ENDJMLCOMMENT;

import org.jmlspecs.openjml.IJmlClauseKind;
import org.jmlspecs.openjml.JmlExtension;
import org.jmlspecs.openjml.JmlTree.JmlAbstractStatement;
import org.jmlspecs.openjml.JmlTree.JmlStatement;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.JmlAttr;
import com.sun.tools.javac.parser.JmlParser;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.util.Context;

/** This class handles expression extensions that take an argument list of JCExpressions.
 * Even if there are constraints on the number of arguments, it
 * is more robust to accept all of them and then issue an error in the typechecker
 * if the number of arguments is wrong.
 * 
 * @author David Cok
 *
 */// TODO: This extension is inappropriately named at present.  However, I expect that this 
// extension will be broken into individual extensions when type checking and
// RAC and ESC translation are added.
public class SetStatement extends JmlExtension.Statement {

    public static final String setID = "set";
    public static final String debugID = "debug";
    
    public IJmlClauseKind[]  clauseTypes() { return new IJmlClauseKind[]{
            setClause, debugClause }; }
    
    public static final IJmlClauseKind setClause = new JmlStatementType() {
        public String name() { return setID; }
    };
    
    public static final IJmlClauseKind debugClause = new JmlStatementType() {
        public String name() { return debugID; }
    };
    

    public static class JmlStatementType extends IJmlClauseKind.Statement {
        public boolean oldNoLabelAllowed() { return true; }
        public boolean preOrOldWithLabelAllowed() { return true; }

        public JmlAbstractStatement parse(JCModifiers mods, String keyword, IJmlClauseKind clauseType, JmlParser parser) {
            init(parser);
            
            int pp = parser.pos();
            int pe = parser.endPos();
            
            scanner.setJmlKeyword(false);
            parser.nextToken();
//            JCExpression e = parser.parseExpression();
//            JCStatement t = jmlF.Exec(e);
            boolean saved = parser.setInJmlDeclaration(true);
            try {
                JCStatement t = parser.parseJavaStatement();
                //          if (!(t instanceof JCExpressionStatement)) {
                //          parser.jmlerror(t.getStartPosition(),
                //                  parser.getEndPos(t),
                //                  "jml.bad.statement.in.set.debug");
                //          t = null;
                //      }
                //      JmlAbstractStatement st = toP(jmlF.at(pp).JmlStatement(clauseType, (JCExpressionStatement)t));
                JmlAbstractStatement st = toP(jmlF.at(pp).JmlStatement(clauseType, t));


                wrapup(st, clauseType, false);
                return st;
            } finally {
                parser.setInJmlDeclaration(saved);
            }
        }
        
        @Override
        public Type typecheck(JmlAttr attr, JCTree expr, Env<AttrContext> env) {
            // TODO Auto-generated method stub
            return null;
        }
}
    
    
}
