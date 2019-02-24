package org.jmlspecs.openjml.ext;

import org.jmlspecs.openjml.IJmlClauseType;
import org.jmlspecs.openjml.JmlSpecs;
import org.jmlspecs.openjml.JmlTokenKind;
import org.jmlspecs.openjml.JmlExtension.ClassLike;
import org.jmlspecs.openjml.JmlTree.JmlClassDecl;
import org.jmlspecs.openjml.JmlTreeCopier;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Flags.Flag;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.parser.JmlParser;
import com.sun.tools.javac.parser.Tokens.Comment;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.parser.Tokens.Comment.CommentStyle;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Pair;

public class DatatypeExt extends ClassLike {

    public static final String name = "datatype";

    @Override
    public String name() {
        return name;
    }

    @Override
    public JmlDatatypeDecl parse(JmlParser parser, JCModifiers xmmods) {
        //init(parser);
        Comment dc = parser.token().comment(CommentStyle.JAVADOC);
        int pos = parser.token().pos;
        JCModifiers mods = parser.modifiersOpt();
        parser.nextToken();
        Name datatypeName = parser.ident(); // Simple name of the datatype
        List<JCTypeParameter> typarams = parser.typeParametersOpt();
        System.out.println("Parsing datatype named " + datatypeName.toString());
        parser.accept(TokenKind.LBRACE);
        Names names = Names.instance(context);
        ListBuffer<JCTree> defs = new ListBuffer<>();
        ListBuffer<Pair<Name,List<JCVariableDecl> >> cons = new ListBuffer<>();
        while (parser.token().kind != TokenKind.RBRACE && parser.token().kind != TokenKind.SEMI && parser.token().kind != TokenKind.EOF) {
            // FIXME - comma required?
            JmlTreeCopier copier = new JmlTreeCopier(context,parser.jmlF);
            if (parser.token().kind == TokenKind.COMMA) parser.nextToken();
            ListBuffer<JCExpression> tyexpr = new ListBuffer<>();
            for (JCTypeParameter tp: typarams) {
                tyexpr.add(parser.jmlF.Ident(tp.name));
            }
            List<JCExpression> tye = tyexpr.toList();
            Name cname = parser.ident();
            List<JCVariableDecl> params = parser.formalParameters();
            cons.add(new Pair<Name,List<JCVariableDecl>>(cname,params));
            JCExpression restype = parser.jmlF.Ident(datatypeName);
            if (!typarams.isEmpty()) restype = parser.jmlF.TypeApply(restype, tye);
            JCModifiers mmodstatic = parser.jmlF.Modifiers(Flags.PUBLIC|Flags.STATIC);
            parser.utils.setJML(mmodstatic);
            JCModifiers mmods = parser.jmlF.Modifiers(Flags.PUBLIC|Flags.ABSTRACT);
            parser.utils.setJML(mmods);
            ListBuffer<JCTypeParameter> ntyparams = new ListBuffer<>();
            for (JCTypeParameter tp: typarams) {
                ntyparams.add(parser.jmlF.TypeParameter(tp.name, tp.bounds));
            }
            JCMethodDecl mdecl = parser.jmlF.MethodDef(mmodstatic,cname,restype,ntyparams.toList(), params, List.<JCExpression>nil(), null, null );
            defs.add(mdecl);
            Name isName = names.fromString("is" + cname.toString());
            mdecl = parser.jmlF.MethodDef(mmods,isName,parser.jmlF.TypeIdent(TypeTag.BOOLEAN),List.<JCTypeParameter>nil(), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), null, null );
            defs.add(mdecl);
            for (JCVariableDecl p: params) {
                mdecl = parser.jmlF.MethodDef(mmods,p.name,copier.copy(p.vartype),List.<JCTypeParameter>nil(), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), null, null );
                defs.add(mdecl);
            }
        }
        if (parser.token().kind == TokenKind.SEMI) {
            parser.nextToken();
            while (parser.token().kind != TokenKind.RBRACE && parser.token().kind != TokenKind.EOF) {
                // Parse a method
                
                List<JCTree> t = parser.classOrInterfaceBodyDeclaration(datatypeName,false);
                // check that this is a method
                if (t != null && !t.isEmpty()) defs.add(t.head);
            }
        }
        parser.accept(TokenKind.RBRACE);
        //mods.flags |= Flags.STATIC; // Implicitly static
        mods.flags |= Flags.ABSTRACT; // Implicitly abstract -- FIXME doe sit need to be if it is model?
        // Implicitly model
        mods.annotations = JmlSpecs.instance(context).addModelAnnotation(pos, mods.annotations);
        // FIXME - make this a novel primitive type
        JmlDatatypeDecl d = new JmlDatatypeDecl(mods, datatypeName, typarams, null, List.<JCExpression>nil(), defs.toList(), null);
        d.constructors = cons.toList();
        d.pos = pos;
        parser.utils.setJML(d.mods);
        while (parser.token().ikind == JmlTokenKind.ENDJMLCOMMENT) parser.nextToken();
        System.out.println(d.toString());
        return d;
        
        // No upper or lower bounds on type parameters
        // Primitive types allowed
    }

    @Override
    public IJmlClauseType[] clauseTypes() {
        return null;
    }
    
    static public class JmlDatatypeDecl extends JmlClassDecl {
        
        public List<Pair<Name,List<JCVariableDecl> >> constructors;
        

        protected JmlDatatypeDecl(JCModifiers mods, Name name,
                List<JCTypeParameter> typarams, JCExpression extending,
                List<JCExpression> implementing, List<JCTree> defs,
                ClassSymbol sym) {
            super(mods, name, typarams, extending, implementing, defs, sym);
            // TODO Auto-generated constructor stub
        }
        
    }

}
