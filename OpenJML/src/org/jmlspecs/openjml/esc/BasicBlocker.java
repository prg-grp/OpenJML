package org.jmlspecs.openjml.esc;

import static com.sun.tools.javac.code.TypeTags.CLASS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;


import org.jmlspecs.openjml.JmlInternalError;
import org.jmlspecs.openjml.JmlSpecs;
import org.jmlspecs.openjml.JmlToken;
import org.jmlspecs.openjml.JmlTree;
import org.jmlspecs.openjml.JmlTreeScanner;
import org.jmlspecs.openjml.JmlSpecs.TypeSpecs;
import org.jmlspecs.openjml.JmlTree.*;
import org.jmlspecs.openjml.esc.BasicProgram.BasicBlock;
import org.jmlspecs.openjml.proverinterface.IProverResult.ICounterexample;

import org.jmlspecs.annotations.*;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Scope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.JmlAttr;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;

import java.util.List;

import javax.tools.JavaFileObject;

/** This class converts a Java AST into basic block form (including DSA and
 * passification).
 * 
 * @author David Cok
 */
public class BasicBlocker extends JmlTreeScanner {

    /** The context key for the basic blocker factory. */
    @NonNull 
    protected static final Context.Key<BasicBlocker> basicBlockerKey =
        new Context.Key<BasicBlocker>();

    /** Get the Factory instance for this context. 
     * 
     * @param context the compilation context
     * @return a (unique for the context) BasicBlocker instance
     */  // FIXME - do we really want to reuse a common instance?
    public static BasicBlocker instance(@NonNull Context context) {
        BasicBlocker instance = context.get(basicBlockerKey);
        if (instance == null)
            instance = new BasicBlocker(context);
        return instance;
    }

    /** The constructor, but use the instance() method to get a new instance,
     * in order to support extension.  This constructor should only be
     * invoked by a derived class constructor.
     * @param context the compilation context
     */
    protected BasicBlocker(@NonNull Context context) {
        this.context = context;
        this.factory = (JmlTree.Maker)JmlTree.Maker.instance(context);
        this.names = Name.Table.instance(context);
        this.syms = Symtab.instance(context);
        this.specs = JmlSpecs.instance(context);
        
        trueLiteral = factory.Literal(TypeTags.BOOLEAN,1);
        trueLiteral.type = syms.booleanType;
        falseLiteral = factory.Literal(TypeTags.BOOLEAN,0);
        falseLiteral.type = syms.booleanType;
        nullLiteral = factory.Literal(TypeTags.BOT,0);
        nullLiteral.type = syms.objectType; // FIXME - object type?
        zeroLiteral = factory.Literal(TypeTags.INT,0);
        zeroLiteral.type = syms.intType;
        
        allocIdent = newAuxIdent("$$alloc",syms.intType,0); // FIXME - magic string
        allocSym = (VarSymbol)allocIdent.sym;

        lengthIdent = newAuxIdent("length$0",syms.intType,0); // FIXME - this magic string is used elsewhere - document
        lengthSym = (VarSymbol)lengthIdent.sym;

        thisId = newAuxIdent("this",syms.objectType,0); // FIXME - object type?
    }
    
    // THE FOLLOWING FIELDS ARE EXPECTED TO BE CONSTANT FOR THE LIFE OF THE OBJECT
    // They are either initialized in the constructor or initialized on first use
    
    /** The compilation context */
    @NonNull protected Context context;
    
    /** The specifications database for this compilation context, initialized in the constructor */
    @NonNull protected JmlSpecs specs;
    
    /** The symbol table from the compilation context, initialized in the constructor */
    @NonNull protected Symtab syms;
    
    /** The Names table from the compilation context, initialized in the constructor */
    @NonNull protected Name.Table names;
    
    /** The factory used to create AST nodes, initialized in the constructor */
    @NonNull protected JmlTree.Maker factory;

    // Caution - the following are handy, but they are shared, so they won't
    // have proper position information
    
    /** Holds an AST node for a boolean true literal, initialized in the constructor */
    @NonNull final protected JCLiteral trueLiteral;
    
    /** Holds an AST node for a boolean false literal, initialized in the constructor */
    @NonNull final protected JCLiteral falseLiteral;
    
    /** Holds an AST node for a null literal, initialized in the constructor */
    @NonNull final protected JCLiteral nullLiteral;
    
    /** Holds an AST node for a null literal, initialized in the constructor */
    @NonNull final protected JCLiteral zeroLiteral;
    
    // These are constant but initialized on beginng the translation of a given
    // method
    
    /** A holding spot for the conditional return block of a program under normal termination */
    protected BasicBlock condReturnBlock;
    
    /** A holding spot for the return block of a program under normal termination */
    protected BasicBlock returnBlock;
    
    /** A holding spot for the conditional exception block (after try-finally) */
    protected BasicBlock condExceptionBlock;
    
    /** A holding spot for the last block of a program when there is an exception */
    protected BasicBlock exceptionBlock;
    
    // THE FOLLOWING FIELDS ARE USED IN THE COURSE OF DOING THE WORK OF CONVERTING
    // TO BASIC BLOCKS.  They are fields of the class because they need to be
    // shared across the visitor methods.
    
    /** visit methods that emit statements put them here */
    protected List<JCStatement> newstatements;  // FIXME - just use currentBlock.statements ???
    
    /** Place to put new definitions, such as the equalities defining auxiliary variables */
    protected List<JCExpression> newdefs;
    
    /** List of blocks yet to be processed (from conventional program to basic program state) */
    protected java.util.List<BasicBlock> blocksToDo;
    
    /** List of blocks completed processing - in basic state */
    protected java.util.List<BasicBlock> blocksCompleted;
    
    /** A map of names to blocks */
    protected java.util.Map<String,BasicBlock> blockLookup;  // FIXME don't need this, I think
    
    /** A variable to hold the block currently being processed */
    protected BasicBlock currentBlock;
    
    /** Ordered list of statements from the current block that are yet to be processed into basic program form */
    private List<JCStatement> remainingStatements;
    
    /** Identifier of a synthesized object field holding the allocation time of the object, initialized in the constructor */
    @NonNull protected JCIdent allocIdent;

    /** Symbol of a synthesized object field holding the allocation time of the object, initialized in the constructor */
    @NonNull protected VarSymbol allocSym;

    /** Identifier of a synthesized object field holding the length of an array object, initialized in the constructor */
    @NonNull protected JCIdent lengthIdent;

    /** Symbol of a synthesized object field holding the length of an array object, initialized in the constructor */
    @NonNull protected VarSymbol lengthSym;
    
    /** The id for the 'this' of the method being translated. */
    @NonNull protected JCIdent thisId;
    
    // FIXME - document the following
    
    protected JCExpression resultVar = null;
    protected JCIdent exceptionVar;
    protected JCIdent allocVar;
    //protected JCIdent stateVar;
    protected JCIdent terminationVar;  // 0=no termination requested; 1=return executed; 2 = exception happening
    
    protected JCIdent assumeCheckCountVar;
    protected int assumeCheckCount; 
    
    /** Holds the result of any of the visit methods that produce JCExpressions, since the visitor
     * template used here does not have a return value.  [We could have used the templated visitor,
     * but other methods do not need to return anything, we don't need the additional parameter,
     * and that visitor is complicated by the use of interfaces for the formal parameters.]
     */
    private JCExpression result;
    
    /** A mapping from BasicBlock to the sym->incarnation map giving the map that
     * FIXME !!!!.  FIXME - change this to a map to the new JCIdent
     */
    @NonNull protected Map<BasicBlock,Map<VarSymbol,Integer>> blockmaps = new HashMap<BasicBlock,Map<VarSymbol,Integer>>();
    
    /** A mapping from labels to the sym->incarnation map operative at the position
     * of the label.
     */
    @NonNull protected Map<Name,Map<VarSymbol,Integer>> labelmaps = new HashMap<Name,Map<VarSymbol,Integer>>();
    
    /** A mapping from labels to the sym->incarnation map operative at the position
     * of the label.
     */
    @NonNull protected Map<Name,JCLabeledStatement> labelmapStatement = new HashMap<Name,JCLabeledStatement>();
    
    /** The map from symbol to incarnation number in current use */
    @NonNull protected Map<VarSymbol,Integer> currentMap;
    
    /** The mapping of variables to incarnations to use when in the scope of an \old */
    @NonNull protected Map<VarSymbol,Integer> oldMap = new HashMap<VarSymbol,Integer>();

    /** The class info block when walking underneath a given class. */
    JmlClassInfo classInfo;
    
    // THE FOLLOWING ARE ALL FIXED STRINGS
    
    public static final @NonNull String blockPrefix = "$$BL$";
    
    /** Standard name for the variable that tracks termination */
    public static final @NonNull String TERMINATION_VAR = "$$terminationVar";
    
    /** Standard name for the block that starts the body */
    public static final @NonNull String BODY_BLOCK_NAME = "$$BL$bodyBegin";
    
    /** Standard name for the starting block of the program (just has the preconditions) */
    public static final @NonNull String START_BLOCK_NAME = "$$BL$Start";
    
    /** Standard name for the return block, whether or not a value is returned */
    public static final @NonNull String RETURN_BLOCK_NAME = "$$BL$return";
    
    /** Standard name for the postcondition block, whether or not a value is returned */
    public static final @NonNull String COND_RETURN_BLOCK_NAME = "$$BL$condReturn";
    
    /** Standard name for the exception block */
    public static final @NonNull String EXCEPTION_BLOCK_NAME = "$$BL$exception";
    
    /** Standard name for the conditional exception block */
    public static final @NonNull String COND_EXCEPTION_BLOCK_NAME = "$$BL$condException";
    
    // METHODS
    
    /** Should not need this when everything is implemented */
    protected void notImpl(JCTree that) {
        System.out.println("NOT IMPLEMENTED: BasicBlocker - " + that.getClass());
    }
    
    /** Called by visit methods that should never be called. */
    protected void shouldNotBeCalled(JCTree that) {
        Log.instance(context).error("esc.internal.error","Did not expect to be calling a " + that.getClass() + " within BasicBlocker");
        throw new JmlInternalError();
    }
    
    // FIXME - document
    protected <T extends JCExpression> T copyInfo(T newnode, T oldnode) {
        newnode.type = oldnode.type;
        // FIXME - store end information?
        return newnode;
    }
    
    // FIXME - document
    protected <T extends JCIdent> T copyInfo(T newnode, T oldnode) {
        newnode.type = oldnode.type;
        newnode.sym = oldnode.sym;
        // FIXME - store end information?
        return newnode;
    }
    
    // THE FOLLOWING METHODS CREATE AST NODES.  Since type checking has
    // already been performed, we make sure that each node gets correct
    // type information assigned.  Also, we give it a reasonable position - 
    // something related to the source code location that occasioned this 
    // new node, even if it has no direct representation in the original source.
    
    /** Makes an int literal
     * @param value the value of the literal
     * @param pos the pseudo source code location of the node
     * @return the new literal
     */
    protected JCLiteral makeLiteral(int value, int pos) {
        JCLiteral lit = factory.at(pos).Literal(TypeTags.INT,value);
        lit.type = syms.intType;
        return lit;
    }
    
    /** Makes an boolean literal
     * @param value the value of the literal
     * @param pos the pseudo source code location of the node
     * @return the new literal
     */
    protected JCLiteral makeLiteral(boolean value, int pos) {
        JCLiteral lit = factory.at(pos).Literal(TypeTags.BOOLEAN,value?1:0);
        lit.type = syms.booleanType;
        return lit;
    }
    
    /** Makes a Jml binary operator node (with boolean result)
     * @param op the binary operator (producing a boolean result), e.g. JmlToken.IMPLIES
     * @param lhs the left-hand expression
     * @param rhs the right-hand expression
     * @param pos the pseudo source code location of the node
     * @return the new node
     */
    protected JCExpression makeJmlBinary(JmlToken op, JCExpression lhs, JCExpression rhs, int pos) {
        JmlBinary e = factory.at(pos).JmlBinary(op,lhs,rhs);
        e.type = syms.booleanType;
        return e;
    }
    
    /** Makes a Java binary operator node (with boolean result)
     * @param op the binary operator (producing a boolean result), e.g. JCTree.EQ
     * @param lhs the left-hand expression
     * @param rhs the right-hand expression
     * @param pos the pseudo source code location of the node
     * @return the new node
     */
    protected JCBinary makeBinary(int op, JCExpression lhs, JCExpression rhs, int pos) {
        JCBinary e = factory.at(pos).Binary(op,lhs,rhs);
        e.type = syms.booleanType;
        return e;
    }
    
    /** Makes a Java unary operator node
     * @param op the unary operator, e.g. JCTree.NOT, JCTree.NEG, JCTree.COMPL, ...
     * @param lhs the left-hand expression
     * @param rhs the right-hand expression
     * @param pos the pseudo source code location of the node
     * @return the new node
     */
    protected JCExpression makeUnary(int op, JCExpression expr, int pos) {
        JCUnary e = factory.at(pos).Unary(op,expr);
        if (op == JCTree.NOT) e.type = syms.booleanType;
        else e.type = syms.intType;  // NEG POS COMPL PREINC PREDEC POSTINC POSTDEC
        return e;
    }
    
    // FIXME - document
    /** Makes a new variable declaration for helper variables in the AST translation;
     * a new VarSymbol is also created in conjunction with the variable
     * @param name the variable name, as it might be used in program text
     * @param type the variable type
     * @param init the initialization expression as it would appear in a declaration
     * @param pos the pseudo source code location for the new node
     * @returns a new JCVariableDecl node
     */
    protected JCVariableDecl makeVariableDecl(Name name, Type type, JCExpression init, int pos) {
        VarSymbol vsym = new VarSymbol(0, name, type, null);
        vsym.pos = pos;
        JCVariableDecl decl = factory.at(pos).VarDef(vsym,init);
        return decl;
    }
    
    /** Creates an encoded name from a symbol and an incarnation position of the form
     *    <symbol name>$<declaration position>$<use position>
     * If the symbol has a negative declaration position, that value is not included in the string
     * @param sym the symbol being given a logical name
     * @param incarnationPosition the incarnation position for which to give a new name
     * @return the new name
     */
    protected Name encodedName(VarSymbol sym, int incarnationPosition) {
        return names.fromString(sym.getQualifiedName() + (sym.pos < 0 ? "$" : ("$" + sym.pos + "$")) + incarnationPosition);
    }
    
    /** Creates an encoded name from a symbol and an incarnation position of the form
     *    <symbol name>$<declaration position>$<use position>
     * If the symbol has a negative declaration position, that value is not included in the string
     * @param sym the symbol being given a logical name
     * @param incarnationPosition the incarnation position for which to give a new name
     * @return the new name
     */
    protected Name encodedName(MethodSymbol sym, int declpos, int incarnationPosition) {
        return names.fromString(sym.getQualifiedName() + (declpos < 0 ? "$" : ("$" + declpos + "$")) + incarnationPosition);
    }
    
    /** Creates an identifier nodes for a new incarnation of the variable, that is,
     * when the variable is assigned to.
     * @param id the old identifier, giving the root name, symbol and type
     * @param incarnationPosition the position (and incarnation number) of the new variable
     * @return the new identifier
     */
    protected JCIdent newIdentIncarnation(JCIdent id, int incarnationPosition) {
        JCIdent n = factory.at(incarnationPosition).Ident(encodedName((VarSymbol)id.sym,incarnationPosition));
        copyInfo(n,id);
        currentMap.put((VarSymbol)id.sym,incarnationPosition);
        return n;
    }
    
    // FIXME - document
    protected JCIdent newArrayIncarnation(Type componentType, int usePosition) {
        String s = "arrays$" + encodeType(componentType);
        JCIdent id = arrayIdMap.get(s);
        if (id == null) {
            id = factory.Ident(names.fromString(s));
            id.pos = 0;
            id.type = componentType;
            id.sym = new VarSymbol(0,id.name,id.type,null);
            arrayIdMap.put(s,id);
        }
        currentMap.put((VarSymbol)id.sym,Integer.valueOf(usePosition));
        id = newIdentUse((VarSymbol)id.sym,usePosition,usePosition);
        return id;
    }
    
    /** Creates an identifier node for a use of a variable at a given source code
     * position and with a given incarnation position.
     * @param sym the underlying symbol (which gives the declaration location)
     * @param useposition the source position of its use
     * @param incarnation the position of the last assignment of this variable
     * @return
     */ // FIXME - not sure anyone should use this - use newIdentIncarnation instead?
    protected JCIdent newIdentUse(VarSymbol sym, int useposition, int incarnation) {
        JCIdent n = factory.at(useposition).Ident(encodedName(sym,incarnation));
        n.sym = sym;
        n.type = sym.type;
        // FIXME - end information?
        return n;
    }
    
    /** Creates an identifier node for a use of a variable at a given source code
     * position; the current incarnation value is used
     * @param sym the underlying symbol (which gives the declaration location)
     * @param useposition the source position of its use
     * @return
     */
    protected JCIdent newIdentUse(VarSymbol sym, int useposition) {
        Integer ipos = currentMap.get(sym);
        int pos = ipos == null? 0 : ipos;
        return newIdentUse(sym,useposition,pos);
    }
    
    /** Creates a newly incarnated variable corresponding to the given declaration.
     * The incarnation number will be the position of the declaration for some
     * declarations, but not, for example, for a formal argument of a method call -
     * then it would be the position of the actual parameter expresssion.
     * @param id the original declaration
     * @param incarnation the incarnation number to use
     * @return the new variable node
     */
    protected JCIdent newIdentIncarnation(JCVariableDecl id, int incarnation) {
        JCIdent n = factory.at(incarnation).Ident(encodedName((VarSymbol)id.sym,incarnation));
        n.sym = id.sym;
        n.type = id.type;
        // FIXME - end information?
        currentMap.put((VarSymbol)id.sym,incarnation);
        return n;
    }
    
    /** Creates a new variable representing an auxiliary variable, for use as a
     * logical variable in the basic program; this is a one-time
     * defined variable - it may not be assigned to again (and thus has no
     * future incarnations)  FIXME - is that true for all uses?
     * @param name the full name of the variable, including any position encoding
     * @param type the type of the variable
     * @param pos the position to assign as its pseudo-location of use
     * @return
     */
    protected JCIdent newAuxIdent(@NonNull String name, @NonNull Type type, int pos) {
        JCIdent id = factory.at(pos).Ident(names.fromString(name));
        id.sym = new VarSymbol(0,id.name,type,null);
        id.type = type;
        return id;
    }
    
    /** Start the processing of the given block
     * 
     * @param b the block for which to initialize processing 
     */
    protected void startBlock(@NonNull BasicBlock b) {
        if (b.id.toString().endsWith("$finally")) tryStack.removeFirst();
        currentBlock = b;
        remainingStatements = currentBlock.statements;
        newstatements = currentBlock.statements = new ArrayList<JCStatement>();
        currentMap = initMap(currentBlock);
    }
    
    /** Files away a completed block, adding it to the blocksCompleted list and
     * to the lookup Map.
     * @param b the completed block
     */
    protected void completed(@NonNull BasicBlock b) {
        if (b == null) return;
        blocksCompleted.add(b);
        blockmaps.put(b,currentMap);
        blockLookup.put(b.id.name.toString(),b);
    }
    
    /** Updates the data structures to indicate that the after block follows the
     * before block
     * @param before block that precedes after
     * @param after block that follows before
     */
    protected void follows(@NonNull BasicBlock before, @NonNull BasicBlock after) {
        before.succeeding.add(after);
        after.preceding.add(before);
    }
    
    /** Inserts the after block after the before block, replacing anything that
     * used to follow the before block
     * @param before
     * @param after
     */
    protected void replaceFollows(@NonNull BasicBlock before, @NonNull BasicBlock after) {
        for (BasicBlock b: before.succeeding) {
            b.preceding.remove(before);
        }
        before.succeeding.clear();
        follows(before,after);
    }
    
    /** This utility method converts an expression from AST to BasicProgram
     * form; the method may have side-effects
     * in creating new statements (in newstatements).  The returned expression
     * node is expected to have position, type and symbol information set.
     * @param expr the expression to convert
     * @return the converted expression
     */
    protected JCExpression trExpr(JCExpression expr) {
        if (expr == null) return null;
        expr.accept(this);
        return result;
    }
    
    /** true when translating a spec expression, which has particular effect on
     * the translation of method calls */
    protected boolean inSpecExpression;
    
    /** This utility method converts an expression from AST to BasicProgram
     * form; the argument is expected to be a spec-expression;
     * the method may have side-effects
     * in creating new statements (in newstatements).  The returned expression
     * node is expected to have position, type and symbol information set.
     * @param expr the expression to convert
     * @return the converted expression
     */
    protected JCExpression trSpecExpr(JCExpression expr) {
        if (expr == null) return null;
        boolean prevInSpecExpression = inSpecExpression;
        inSpecExpression = true;
        try {
            expr.accept(this);
            return result;
        } finally {
            inSpecExpression = prevInSpecExpression;
        }
    }
    
    /** Static entry point that converts an AST (the block of a method) into basic block form
     * 
     * @param context the compilation context
     * @param tree the block of a method
     * @param denestedSpecs the specs of the method
     * @param classInfo the info about the enclosing class
     * @return the resulting BasicProgram
     */
    protected static @NonNull BasicProgram convertToBasicBlocks(@NonNull Context context, 
            @NonNull JCMethodDecl tree, JmlMethodSpecs denestedSpecs, JCClassDecl classDecl) {
        BasicBlocker blocker = instance(context);
        return blocker.convertMethodBody(tree,denestedSpecs,classDecl);
    }
    
    /** Returns a new, empty BasicBlock
     * 
     * @param name the name to give the block
     * @param pos a position to associate with the JCIdent for the block
     * @return the new block
     */
    protected @NonNull BasicBlock newBlock(@NonNull String name, int pos) {
        JCIdent id = newAuxIdent(name,syms.booleanType,pos);
        BasicBlock bb = new BasicBlock(id);
        blockLookup.put(id.name.toString(),bb);
        return bb;
    }
    
    /** Returns a new, empty BasicBlock, but the new block takes all of the 
     * followers of the given block; the previuosBlock will then have no
     * followers.
     * 
     * @param name the name to give the block
     * @param pos a position to associate with the JCIdent for the block
     * @param previousBlock the block that is giving up its followers
     * @return the new block
     */
    protected @NonNull BasicBlock newBlock(@NonNull String name, int pos, @NonNull BasicBlock previousBlock) {
        JCIdent id = newAuxIdent(name,syms.booleanType,pos);
        return new BasicBlock(id,previousBlock);
    }
    
    // characteristics of the method under study
    boolean isConstructor;
    boolean isStatic;
    boolean isHelper;

    /** Converts the top-level block of a method into the elements of a BasicProgram 
     * 
     * @param tree the method to convert to to a BasicProgram
     * @param denestedSpecs the specs of the method
     * @param classDecl the declaration of the containing class
     * @return the completed BasicProgram
     */
    protected @NonNull BasicProgram convertMethodBody(@NonNull JCMethodDecl tree, 
            JmlMethodSpecs denestedSpecs, @NonNull JCClassDecl classDecl) {
        isConstructor = tree.sym.isConstructor();  // FIXME - careful if there is nesting???
        isStatic = tree.sym.isStatic();
        isHelper = isHelper(tree.sym);
        int pos = tree.pos;
        inSpecExpression = false;
        JmlClassInfo classInfo = getClassInfo(classDecl.sym);
        this.classInfo = classInfo;
        newdefs = new LinkedList<JCExpression>();
        blocksToDo = new LinkedList<BasicBlock>();
        blocksCompleted = new ArrayList<BasicBlock>();
        blockLookup = new java.util.HashMap<String,BasicBlock>();
        if (tree.getReturnType() != null) {
            resultVar = newAuxIdent("$$result",tree.getReturnType().type,0); 
        }
        terminationVar = newAuxIdent(TERMINATION_VAR,syms.intType,0);
        exceptionVar = newAuxIdent("$$exception",syms.exceptionType,0);
        allocVar = newAuxIdent("$$alloc",syms.intType,0); // FIXME - would this be better as its own uninterpreted type?
        //stateVar = newAuxIdent("$$state",syms.intType,0); // FIXME - would this be better as its own uninterpreted type?
        assumeCheckCountVar = newAuxIdent("$$assumeCheckCount",syms.intType,0);
        assumeCheckCount = 0;
        
        JCBlock block = tree.getBody();
        
        // Define the conditional return block
        condReturnBlock = newBlock(COND_RETURN_BLOCK_NAME,pos);
        JCExpression e = makeBinary(JCTree.GT,terminationVar,zeroLiteral,pos);
        JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,e);
        condReturnBlock.statements.add(asm);
        
        // Define the return block
        returnBlock = newBlock(RETURN_BLOCK_NAME,pos);
        follows(condReturnBlock,returnBlock);
        
        // Define the conditional exception block
        condExceptionBlock = newBlock(COND_EXCEPTION_BLOCK_NAME,pos);
        e = makeBinary(JCTree.LT,terminationVar,zeroLiteral,pos);
        asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,e);
        condExceptionBlock.statements.add(asm);
        
        // Define the exception block
        exceptionBlock = newBlock(EXCEPTION_BLOCK_NAME,pos);
        follows(condExceptionBlock,exceptionBlock);
        
        // Define the start block
        BasicBlock startBlock = newBlock(START_BLOCK_NAME,pos);
        
        // Define the body block
        // Put all the program statements in the Body Block
        BasicBlock bodyBlock = newBlock(BODY_BLOCK_NAME,tree.body.pos);
        // First a couple key statements
        e = makeBinary(JCTree.EQ,terminationVar,zeroLiteral,tree.body.pos);
        asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,e);
        bodyBlock.statements.add(asm);
        e = makeBinary(JCTree.EQ,exceptionVar,nullLiteral,tree.body.pos);
        asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,e);
        bodyBlock.statements.add(asm);
        // Then the program
        bodyBlock.statements.addAll(block.getStatements());
        follows(startBlock,bodyBlock);
        follows(bodyBlock,returnBlock); // implicit, unconditional return
        
        // Put the blocks in the todo list
        blocksToDo.add(0,exceptionBlock);
        blocksToDo.add(0,condExceptionBlock);
        blocksToDo.add(0,returnBlock);
        blocksToDo.add(0,condReturnBlock);
        blocksToDo.add(0,bodyBlock);
        
        // Handle the start block a little specially
        // It does not have any statements in it
        startBlock(startBlock);
        addPreconditions(startBlock,tree,denestedSpecs);
        completed(currentBlock);

        // Pick a block to do and process it
        while (!blocksToDo.isEmpty()) {
            processBlock(blocksToDo.remove(0));
        }
        addPostconditions(returnBlock,tree,denestedSpecs);
        
        // Make the BasicProgram
        BasicProgram program = new BasicProgram();
        program.startId = startBlock.id;
        program.blocks.addAll(blocksCompleted);
        program.definitions = newdefs;
        program.assumeCheckVar = assumeCheckCountVar;
        
        // Find all the variables so they can be declared if necessary
        Set<JCIdent> vars = new HashSet<JCIdent>();
        for (BasicBlock bb : blocksCompleted) {
            VarFinder.findVars(bb.statements,vars);
        }
        for (JCExpression ex : newdefs) {
            VarFinder.findVars(ex,vars);
        }
        Collection<JCIdent> decls = program.declarations;
        Set<Name> varnames = new HashSet<Name>();
        for (JCIdent id: vars) {
            if (varnames.add(id.getName())) decls.add(id);
        }
        return program;
    }
    
    /** Does the conversion of a block with Java statements into basic program
     * form, possibly creating new blocks on the todo list
     * @param block the block to process
     */
    protected void processBlock(@NonNull BasicBlock block) {
        startBlock(block);
        processBlockStatements(true);
    }
    
    /** Iterates through the statements on the remainingStatements list, processing them
     * 
     * @param complete call 'completed' on the currentBlock, if true
     */
    protected void processBlockStatements(boolean complete) {
        while (!remainingStatements.isEmpty()) {
            JCStatement s = remainingStatements.remove(0);
            s.accept(this);
        }
        if (complete) completed(currentBlock);
    }
    
    /** A cache for the symbol */
    private ClassSymbol helperAnnotationSymbol = null;
    /** Returns true if the given symbol has a helper annotation
     * 
     * @param symbol the symbol to check
     * @return true if there is a helper annotation
     */
    protected boolean isHelper(@NonNull Symbol symbol) {
        if (helperAnnotationSymbol == null) {
            helperAnnotationSymbol = ClassReader.instance(context).
                enterClass(names.fromString("org.jmlspecs.annotations.Helper"));
        }
        return symbol.attribute(helperAnnotationSymbol)!=null;
    }  // FIXME - isn't there a utility method somewhere else that does this

    
    /** Inserts assumptions corresponding to the preconditions into the given block.
     * Uses classInfo to get the class-level preconditions.
     * 
     * @param b      the block into which to add the assumptions
     * @param tree   the method being translated
     * @param denestedSpecs  the denested specs for that method
     */
    protected void addPreconditions(@NonNull BasicBlock b, @NonNull JCMethodDecl tree, @NonNull JmlMethodSpecs denestedSpecs) {
        
        addClassPreconditions(classInfo,b);

        JCExpression expr = falseLiteral;
        MethodSymbol msym = tree.sym;
        JmlMethodInfo mi = getMethodInfo(msym);
        if (mi.requiresPredicates.size() == 0) expr = trueLiteral;
        else for (JCExpression pre: mi.requiresPredicates) {
            pre = trSpecExpr(pre);
            if (expr == falseLiteral) expr = pre;
            else {
                expr = makeBinary(JCTree.OR,expr,pre,expr.pos);
            }
        }
        expr.pos = expr.getStartPosition();
        
            // Need definedness checks?  FIXME
        if (!isConstructor && !isStatic) {
            while ((msym=getOverrided(msym)) != null) {
                expr = addMethodPreconditions(b,msym,tree,tree.pos,expr);
            }
        }
        
        JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.PRECONDITION,expr);
        b.statements.add(asm);

    }
    
    protected JCExpression addMethodPreconditions(@NonNull BasicBlock b, @NonNull MethodSymbol msym, @NonNull JCMethodDecl baseMethod, int pos, JCExpression expr) {

        // FIXME - argument names???  Will the pre and post names be different?
        JmlMethodInfo mi = getMethodInfo(msym);
        if (msym != baseMethod.sym) {
            addParameterMappings(baseMethod,mi.decl,pos,b);
        }

        if (mi.requiresPredicates.size() == 0) expr = trueLiteral;
        else for (JCExpression pre: mi.requiresPredicates) {
            int p = expr.pos == 0 ? pre.getStartPosition() : expr.pos;
            pre = trSpecExpr(pre);
            expr = makeBinary(JCTree.OR,expr,pre,p);
            //JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.PRECONDITION,trSpecExpr(pre));
            //b.statements.add(asm);
        }

        return expr;
    }
    
    protected void addClassPreconditions(JmlClassInfo cInfo, BasicBlock b) {
        Type type = cInfo.csym.getSuperclass();
        if (type != null) {
            JmlClassInfo ci = getClassInfo(type.tsym);
            if (ci != null) addClassPreconditions(ci,b);
        }
        ClassSymbol csym = cInfo.csym;
        JmlClassInfo prevClassInfo = classInfo;
        classInfo = cInfo; // Set the global value so trExpr calls have access to it
        try {
            // The axioms should perhaps be part of a class predicate?  // FIXME
            for (JmlTypeClauseExpr ax : cInfo.axioms) {
                JCExpression e = ax.expression;
                e = trSpecExpr(e);
                JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.INVARIANT,e);
                b.statements.add(asm);
            }

            if (!isConstructor && !isHelper) {
                for (JmlTypeClauseExpr inv : cInfo.staticinvariants) {
                    JCExpression e = inv.expression;
                    e = trSpecExpr(e);
                    JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.INVARIANT,e);
                    b.statements.add(asm);
                }
                if (!isStatic) {
                    for (JmlTypeClauseExpr inv : cInfo.invariants) {
                        JCExpression e = inv.expression;
                        e = trSpecExpr(e);
                        JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.INVARIANT,e);
                        b.statements.add(asm);
                    }
                }
            }
        } finally {
            classInfo = prevClassInfo;
        }
    }
    
    boolean useAuxDefinitions = true;

//    protected void addAssert(Label label, JCExpression that, List<JCStatement> statements, int usepos) {
//        addAssert(label,that,that.pos,statements,usepos);
//    }
    
    protected JCExpression addAssert(Label label, JCExpression that, int declpos, List<JCStatement> statements, int usepos) {
        JmlStatementExpr st;
        if (useAuxDefinitions) {
            String n = "assert$" + usepos + "$" + declpos + "$" + label;
            JCExpression id = newAuxIdent(n,syms.booleanType,that.getStartPosition());
            JCExpression expr = makeBinary(JCTree.EQ,id,that,that.pos);
                    // FIXME - start and end?
            newdefs.add(expr);
            that = id;
        }
        st = factory.JmlExpressionStatement(JmlToken.ASSERT,label,that);
        st.pos = that.pos; // FIXME - start and end?
        st.optionalExpression = null;
        st.type = null; // FIXME - is this right?
        // FIXME - what about source and line?
        statements.add(st);
        return that;
    }
    
    // non-tracked
    protected void addAssume(Label label, JCExpression that, List<JCStatement> statements, boolean useAuxDefinition) {
        if (useAuxDefinition) {
            
        }
        that.type = syms.booleanType;
        JCStatement st = factory.JmlExpressionStatement(JmlToken.ASSUME,label,that);
        st.pos = that.pos;
        statements.add(st);
    }
    
    static public String encodeType(Type t) {   // FIXME String? char? void? unsigned?
        if (t instanceof ArrayType) {
            return "array$" + encodeType(((ArrayType)t).getComponentType());
        } else if (!t.isPrimitive()) {
            return "REF";
        } else if (t.tag == TypeTags.INT || t.tag == TypeTags.SHORT || t.tag == TypeTags.LONG || t.tag == TypeTags.BYTE) {
            return "int";
        } else if (t.tag == TypeTags.BOOLEAN) {
            return "bool";
        } else if (t.tag == TypeTags.FLOAT || t.tag == TypeTags.DOUBLE) {
            return "real";
        } else {
            return "unknown";
        }
    }
    
    private Map<String,JCIdent> arrayIdMap = new HashMap<String,JCIdent>();
    protected JCIdent getArrayIdent(Type componentType) {
        String s = "arrays$" + encodeType(componentType);
        JCIdent id = arrayIdMap.get(s);
        if (id == null) {
            id = factory.Ident(names.fromString(s));
            id.pos = 0;
            id.type = componentType;
            id.sym = new VarSymbol(0,id.name,id.type,null);
            arrayIdMap.put(s,id);
        }
        id = newIdentUse((VarSymbol)id.sym,0);
        return id;
    }
    
    /** Creates a auxiliary variable and inserts an assumption about its value.
     * Any new generated statements are added into the currentBlock
     * @param name the name of the auxiliary variable, including any label and position encoding
     * @param type the type of the variable (e.g. syms.booleanType)
     * @param expr the (untranslated) value of the variable
     * @returns the variable corresponding the the given String argument
     */
    // FIXME - modifies the content of currentBlock.statements
    protected @NonNull JCIdent addAuxVariable(@NonNull String name, @NonNull Type type, @NonNull JCExpression expr, boolean makeDefinition) {
        JCExpression newexpr = trExpr(expr);
        JCIdent vd = newAuxIdent(name,type,newexpr.getStartPosition());
        // FIXME - use a definition?
        if (makeDefinition) {
            newdefs.add(makeBinary(JCTree.EQ,vd,newexpr,newexpr.pos));
        } else {
            JmlTree.JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,makeBinary(JCTree.EQ,vd,newexpr,newexpr.pos));
            asm.pos = expr.getStartPosition();  // FIXME - start and end position?
            currentBlock.statements.add(asm);
        }
        return vd;
    }

    protected void addPostconditions(BasicBlock b, JCMethodDecl decl, JmlMethodSpecs denestedSpecs) {
        currentBlock = b;
        currentMap = blockmaps.get(b);

        addMethodPostconditions(decl.sym,b,decl.pos,decl);

        if (!isConstructor && !isStatic) {
            MethodSymbol msym = decl.sym;
            while ((msym = getOverrided(msym)) != null) {
                addMethodPostconditions(msym,b,decl.pos,decl);
            }
        }


        
        // FIXME - reevaluate the last argument: this is the location that the error message
        // indicates as where the assertion failed - it could be the return statement, or the
        // ending close paren.  But the only information we have at this point is the preferred
        // location of the method declaration (unless we could get the ending information).
        addClassPostconditions(classInfo,b,decl.pos);

        // FIXME _ need inherited method preconditions
        // FIXME - this is wrong - we assume th OR of everything
            // Need definedness checks?  FIXME
    }
    
    protected void addMethodPostconditions(MethodSymbol msym, BasicBlock b, int pos, JCMethodDecl baseMethod) {
        List<JCStatement> statements = b.statements;

        // FIXME - argument names???  Will the pre and post names be different?
        JmlMethodInfo mi = getMethodInfo(msym);
        if (msym != baseMethod.sym) {
            addParameterMappings(baseMethod,mi.decl,pos,b);
        }
        for (JCExpression post: mi.ensuresPredicates) {
            addAssert(Label.POSTCONDITION,trSpecExpr(post),post.getStartPosition(),statements,pos);
        }

    }
    
    protected void addClassPostconditions(JmlClassInfo cInfo, BasicBlock b, int usepos) {
        Type type = cInfo.csym.getSuperclass();
        if (type != null) {
            JmlClassInfo ci = getClassInfo(type.tsym);
            if (ci != null) addClassPostconditions(ci,b,usepos);
        }
        ClassSymbol csym = cInfo.csym;
        JmlClassInfo prevClassInfo = classInfo;

        currentBlock = b;
        currentMap = blockmaps.get(b);
        List<JCStatement> statements = b.statements;
        if (!isHelper) {
            for (JmlTypeClauseExpr inv : classInfo.staticinvariants) {
                JCExpression e = inv.expression;
                e = trSpecExpr(e);
                addAssert(Label.INVARIANT,e,inv.expression.getStartPosition(),statements,usepos);
            }
            if (!isStatic) {
                for (JmlTypeClauseExpr inv : classInfo.invariants) {
                    JCExpression e = inv.expression;
                    e = trSpecExpr(e);
                    addAssert(Label.INVARIANT,e,inv.expression.getStartPosition(),statements,usepos);
                }
            }
            if (!isConstructor) {
                for (JmlTypeClauseConstraint inv : classInfo.staticconstraints) {
                    JCExpression e = inv.expression;
                    e = trSpecExpr(e);
                    addAssert(Label.CONSTRAINT,e,inv.expression.getStartPosition(),statements,usepos);
                }
                if (!isStatic) {
                    for (JmlTypeClauseConstraint inv : classInfo.constraints) {
                        JCExpression e = inv.expression;
                        e = trSpecExpr(e);
                        addAssert(Label.CONSTRAINT,e,inv.expression.getStartPosition(),statements,usepos);
                    }
                }
            } else {
                for (JmlTypeClauseExpr inv : classInfo.initiallys) {
                    JCExpression e = inv.expression;
                    e = trSpecExpr(e);
                    addAssert(Label.INITIALLY,e,inv.expression.getStartPosition(),statements,usepos);
                }
            }
        }
    }
    
    protected MethodSymbol getOverrided(MethodSymbol msym) {
        Types types = Types.instance(context);
        for (Type t = types.supertype(msym.owner.type); t.tag == CLASS;
                            t = types.supertype(t)) {
            TypeSymbol c = t.tsym;
            Scope.Entry e = c.members().lookup(msym.name);
            while (e.scope != null) {
                if (msym.overrides(e.sym, (TypeSymbol)msym.owner, types, false)) {
                    return (MethodSymbol)e.sym;
                }
                e = e.next();
            }
        }
        return null;
    }
    
    protected void addParameterMappings(JCMethodDecl baseMethod, JCMethodDecl otherMethod, int pos, BasicBlock b) {
        Iterator<JCVariableDecl> baseParams = baseMethod.params.iterator();
        Iterator<JCVariableDecl> newParams = otherMethod.params.iterator();
        while (baseParams.hasNext()) {
            JCVariableDecl base = baseParams.next();
            JCVariableDecl newp = newParams.next();
            JCIdent baseId = newIdentUse(base.sym,pos);
            JCIdent newId = newIdentIncarnation(newp,0);
            JCExpression eq = makeBinary(JCTree.EQ,newId,baseId,pos);
            addAssume(Label.SYN,eq,b.statements,false);
        }
    }
    
    protected Map<VarSymbol,Integer> initMap(BasicBlock block) {
        Map<VarSymbol,Integer> newMap = new HashMap<VarSymbol,Integer>();
        if (block.preceding.size() == 0) {
            // keep the empty one
        } else if (block.preceding.size() == 1) {
            newMap.putAll(blockmaps.get(block.preceding.get(0))); 
        } else {
            List<Map<VarSymbol,Integer>> all = new LinkedList<Map<VarSymbol,Integer>>();
            Map<VarSymbol,Integer> combined = new HashMap<VarSymbol,Integer>();
            for (BasicBlock b : block.preceding) {
                Map<VarSymbol,Integer> m = blockmaps.get(b);
                all.add(m);
                combined.putAll(m);
            }
            for (VarSymbol sym: combined.keySet()) {
                int max = -1;
                for (Map<VarSymbol,Integer> m: all) {
                    Integer i = m.get(sym);
                    if (i != null && i > max) max = i;
                }
                for (BasicBlock b: block.preceding) {
                    Map<VarSymbol,Integer> m = blockmaps.get(b);
                    Integer i = m.get(sym);
                    if (i == null) i = 0;
                    if (i < max) {
//                        ProgVarDSA pold = new ProgVarDSA(sym,-1);
//
//                        pold.incarnation = i;
//                        ProgVarDSA pnew = new ProgVarDSA(sym,-1);
//                        pnew.incarnation = max;
                        // No position information for these nodes
                        // Type information put in, though I don't know that we need it
                        JCIdent pold = newIdentUse(sym,0,i);
                        JCIdent pnew = newIdentUse(sym,0,max);
                        JCBinary eq = makeBinary(JCTree.EQ,pnew,pold,0);
                        JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,eq);
                        b.statements.add(asm);
                        m.put(sym,max);
                    }
                }
                newMap.put(sym,max);
            }
        }
        return newMap;
    }
    

    static public class JmlMethodInfo {
        public JmlMethodInfo(JCMethodDecl d) { 
            this.decl = d; 
            this.owner = d.sym; 
            this.source = ((JmlMethodDecl)d).sourcefile;
        }
        MethodSymbol owner;
        JCMethodDecl decl;
        JmlClassInfo classInfo;
        JavaFileObject source;
        String resultName;
        boolean resultUsed = false;
        JCExpression exceptionDecl;
        VarSymbol exceptionLocal;
        ListBuffer<JCVariableDecl> olds = new ListBuffer<JCVariableDecl>();
        java.util.List<JCExpression> requiresPredicates = new LinkedList<JCExpression>();
        java.util.List<JCExpression> ensuresPredicates = new LinkedList<JCExpression>();
        
        public static class Entry {
            public Entry(JCExpression pre, java.util.List<JCTree> list) {
                this.pre = pre;
                this.storerefs = list;
            }
            public JCExpression pre;
            public java.util.List<JCTree> storerefs;
        }
        
        java.util.List<Entry> assignables = new LinkedList<Entry>();
    }

    Map<Symbol,JmlMethodInfo> methodInfoMap = new HashMap<Symbol,JmlMethodInfo>();

    JmlMethodInfo getMethodInfo(MethodSymbol msym) {
        JmlMethodInfo mi = methodInfoMap.get(msym);
        if (mi == null) {
            mi = computeMethodInfo(msym);
            methodInfoMap.put(msym,mi);
        }
        return mi;
    }


    // FIXME - when run standalone (not in Eclipse), this method is called with the Object constructor 
    // as its argument, but with method.sym == null - is this because it is Binary?  is it not seeing the specs?
    protected JmlMethodInfo computeMethodInfo(MethodSymbol msym) {
        JmlMethodSpecs mspecs = JmlSpecs.instance(context).getSpecs(msym);
        if (mspecs == null) {
            System.out.println("METHOD SPECS IS UNEXPECTEDLY NULL " + msym);
            return null;
        }
        if (mspecs.decl == null) {
            System.out.println("METHOD DECL IS UNEXPECTEDLY NULL " + msym);
        }
        JmlMethodInfo mi = new JmlMethodInfo(mspecs.decl);
        JmlMethodSpecs denestedSpecs = msym == null ? null : specs.getDenestedSpecs(msym);
        if (JmlEsc.escdebug) System.out.println("SPECS FOR " + msym + " " + (denestedSpecs != null) + " " + msym);
        if (JmlEsc.escdebug) System.out.println(denestedSpecs == null ? "     No denested Specs" : denestedSpecs.toString("   "));

        List<JCStatement> prev = newstatements;
        newstatements = new LinkedList<JCStatement>();
        if (denestedSpecs != null) {
            // preconditions
            JCExpression pre = denestedSpecs.cases.size() == 0 ? makeLiteral(true,mspecs.decl==null?0:mspecs.decl.pos) : null;
            int num = 0;
            for (JmlSpecificationCase spc: denestedSpecs.cases) {
                JCExpression spre = null;
                for (JmlMethodClause c: spc.clauses) {
                    if (c.token == JmlToken.REQUIRES) {
                        num++;
                        JCExpression e = (((JmlMethodClauseExpr)c).expression);
                        if (spre == null) spre = e;
                        else spre = factory.at(spre.pos).Binary(JCTree.AND,spre,e);
                    }
                    if (spre == null) spre = makeLiteral(true,spc.pos);
                }
                if (pre == null) pre = spre;
                else pre = makeBinary(JCTree.OR,pre,spre,pre.pos);
            }
            mi.requiresPredicates.add(pre);  // Just one composite precondition for all of the spec cases
            
            // postconditions
            for (JmlSpecificationCase spc: denestedSpecs.cases) {
                JCExpression spre = trueLiteral;
                for (JmlMethodClause c: spc.clauses) {
                    // FIXME - need definedness checks ??
                    if (c.token == JmlToken.REQUIRES) {
                        int pos = spre==trueLiteral ? c.pos : spre.pos;
                        spre = makeBinary(JCTree.AND,spre,(((JmlMethodClauseExpr)c).expression),pos);
                    }
                }
                JCExpression nspre = factory.Apply(null,factory.JmlFunction(JmlToken.BSOLD),com.sun.tools.javac.util.List.of(spre));
                nspre.type = spre.type;
                spre = nspre;
                for (JmlMethodClause c: spc.clauses) {
                    if (c.token == JmlToken.ENSURES) {
                        JCExpression e = ((JmlMethodClauseExpr)c).expression;
                        JCExpression post = makeJmlBinary(JmlToken.IMPLIES,spre,e,e.getStartPosition());
                        // FIXME - need definedness checks ??
                        mi.ensuresPredicates.add(post);
                    } else if (c.token == JmlToken.ASSIGNABLE) {
                        JmlMethodClauseAssignable mod = (JmlMethodClauseAssignable)c;
                        // spre is the precondition under which the store-refs are modified
                        List<JCTree> list = mod.list; // store-ref expressions
                        mi.assignables.add(new JmlMethodInfo.Entry(spre,list));
                    }
                }
            }
        }
        newstatements = prev;
        return mi;
    }


    // STATEMENT NODES
    
    // Just process all the statements in the block prior to any other
    // remaining statements 
    public void visitBlock(JCBlock that) {
        List<JCStatement> s = that.getStatements();
        if (s != null) remainingStatements.addAll(0,s);
    }
    
    public void visitDoLoop(JCDoWhileLoop that)          { notImpl(that); }
    
    public void visitJmlWhileLoop(JmlWhileLoop that)  { 
        visitLoopWithSpecs(that, null, that.cond, null, that.body, that.loopSpecs);
    }
    
    public void visitWhileLoop(JCWhileLoop that) {
        visitLoopWithSpecs(that, null, that.cond, null, that.body, null);
    }
    
    public void visitJmlForLoop(JmlForLoop that) {
        visitLoopWithSpecs(that,that.init,that.cond,that.step,that.body,that.loopSpecs );
    }
    
    public void visitForLoop(JCForLoop that) { 
        visitLoopWithSpecs(that,that.init,that.cond,that.step,that.body,null );
    }
    
    /* for (Init; Test; Update) S
     * becomes
     * LoopStart: - is actually the end of the current Block
     *   Init;
     *   assert loop invariants
     *   havoc any loop modified variables
     *   assume loop invariants
     *   compute loop condition (which may have side effects creating other statements)
     *   goto LoopBody, LoopEnd
     * LoopBody:
     *   assume loop condition value
     *   compute decreasing, check that it is >= 0
     *   S  [ break -> LoopBreak; continue -> LoopContinue ]
     *   goto LoopContinue
     * LoopContinue:  <-- all continues go here
     *   Update;
     *   assert loop invariants
     *   check that decreasing is less
     * LoopEnd:
     *   assume !(loop condition value) 
     *   goto LoopBreak
     * LoopBreak: <-- all breaks go here
     *   //assert loop invariants 
     *   goto rest...
     */ // FIXME - allow for unrolling
    public void visitLoopWithSpecs(JCTree that, List<JCStatement> init, JCExpression test, List<JCExpressionStatement> update, JCStatement body, List<JmlStatementLoop> loopSpecs) {
        int pos = that.pos;
        BasicBlock bloopBody = newBlock(blockPrefix + pos + "$LoopBody",pos);
        BasicBlock bloopContinue = newBlock(blockPrefix + pos + "$LoopContinue",pos);
        BasicBlock bloopEnd = newBlock(blockPrefix + pos + "$LoopEnd",pos);
        BasicBlock bloopBreak = newBlock(blockPrefix + pos + "$LoopBreak",pos);
        String restName = blockPrefix + pos + "$LoopAfter";

        // Now create an (unprocessed) block for everything that follows the
        // if statement
        BasicBlock brest = newBlock(restName,pos,currentBlock);// it gets all the followers of the current block
        brest.statements = remainingStatements; // it gets all of the remaining statements
        remainingStatements.clear();
        blocksToDo.add(0,brest); // push it on the front of the to do list

        // Finish out the current block with the loop initialization
        if (init != null) remainingStatements.addAll(init);
        processBlockStatements(false);
        addLoopInvariants(JmlToken.ASSERT,loopSpecs,that.getStartPosition());
        {
            // Now havoc needed variables
            List<JCExpression> targets = TargetFinder.findVars(body,null);
            TargetFinder.findVars(test,targets);
            if (update != null) TargetFinder.findVars(update,targets);
            // synthesize a modifies list
            int wpos = body.pos;
            for (JCExpression e: targets) {
                if (e instanceof JCIdent) {
                    newIdentIncarnation((JCIdent)e,wpos);
                } else {
                    // FIXME - havoc in loops
                    System.out.println("UNIMPLEMENTED HAVOC IN LOOP " + e.getClass());
                }
            }
        }
        addLoopInvariants(JmlToken.ASSUME,loopSpecs,that.getStartPosition());
        if (loopSpecs != null) for (JmlStatementLoop loopspec: loopSpecs) {
            if (loopspec.token == JmlToken.DECREASES) {
                String dec = "$$decreases" + "$" + loopspec.getStartPosition();
                addAuxVariable(dec,syms.longType,loopspec.expression,false);
            }
        }
        String loopTestVarName = "forCondition"  
            + "$" + test.getStartPosition() + "$" + test.getStartPosition(); // FIXME - end position?
        JCIdent loopTest = addAuxVariable(loopTestVarName,syms.booleanType,test,false);
        completed(currentBlock);
        BasicBlock bloopStart = currentBlock;
        follows(bloopStart,bloopBody);
        follows(bloopStart,bloopEnd);

        // Create the loop body block
        startBlock(bloopBody);
        addAssume(Label.LOOP,loopTest,bloopBody.statements,false);
        if (loopSpecs != null) for (JmlStatementLoop loopspec: loopSpecs) {
            if (loopspec.token == JmlToken.DECREASES) {
                int p = loopspec.getStartPosition();
                String dec = "$$decreases" + "$" + p;
                JCIdent v = newAuxIdent(dec,syms.longType,p);
                JCExpression e = factory.at(p).Binary(JCTree.GE,v,makeLiteral(0,p));
                addAssert(Label.LOOP_DECREASES_NEGATIVE,e,p,currentBlock.statements,body.getStartPosition()); // FIXME - track which continue is causing a problem?
            }
        }
        remainingStatements.add(body);
        follows(bloopBody,bloopContinue);
        processBlockStatements(true);
        
        // Create a loop continue block
        startBlock(bloopContinue);
        if (update != null) remainingStatements.addAll(update);
        processBlockStatements(false);
        // Check that loop invariants are still established
        addLoopInvariants(JmlToken.ASSERT,loopSpecs,body.getStartPosition()); // FIXME - use end position?
        // Check that loop variants are decreasing
        if (loopSpecs != null) for (JmlStatementLoop loopspec: loopSpecs) {
            if (loopspec.token == JmlToken.DECREASES) {
                String dec = "$$decreases" + "$" + loopspec.getStartPosition();
                JCIdent id = newAuxIdent(dec,syms.longType,loopspec.getStartPosition());
                JCExpression newexpr = trExpr(loopspec.expression);
                JCExpression e = factory.Binary(JCTree.LT,newexpr,id);
                addAssert(Label.LOOP_DECREASES,e,loopspec.getStartPosition(),currentBlock.statements,body.getStartPosition()); // FIXME - track which continue is causing a problem?
            }
        }
        completed(bloopContinue);
        
        // Create the LoopEnd block
        startBlock(bloopEnd);
        follows(bloopEnd,bloopBreak);
        JCExpression neg = makeUnary(JCTree.NOT,loopTest,loopTest.pos);
        addAssume(Label.LOOP,neg,currentBlock.statements,false);
        completed(bloopEnd);
        
        // fill in the LoopBreak block
        startBlock(bloopBreak);
        follows(bloopBreak,brest);
        addLoopInvariants(JmlToken.ASSERT,loopSpecs,body.getStartPosition()+1); // FIXME _ use end position - keep different from Continue
        completed(bloopBreak);

        // Go on processing the loop body
        currentBlock = null;
        newstatements = null;
    }

    protected void addLoopInvariants(JmlToken t, java.util.List<JmlStatementLoop> loopSpecs, int usepos) {
        if (loopSpecs == null) return;
        for (JmlStatementLoop loopspec: loopSpecs) {
            if (loopspec.token == JmlToken.LOOP_INVARIANT) {
                JCExpression e = trSpecExpr(loopspec.expression);
                if (t == JmlToken.ASSUME) addAssume(Label.LOOP_INVARIANT,e,currentBlock.statements,false);
                else addAssert(Label.LOOP_INVARIANT,e,loopspec.getStartPosition(),currentBlock.statements,usepos);
            }
        }
    }
    
    public void visitJmlEnhancedForLoop(JmlEnhancedForLoop that) {
        visitForeachLoopWithSpecs(that,that.loopSpecs);
    }

    public void visitForeachLoop(JCEnhancedForLoop that) {
        visitForeachLoopWithSpecs(that,null);
    }
    
    protected void visitForeachLoopWithSpecs(JCEnhancedForLoop that, List<JmlStatementLoop> loopSpecs) {
        int pos = that.pos;
        if (that.expr.type.tag == TypeTags.ARRAY) {
            // for (T v; arr) S
            // becomes
            //   for (int i=0; i<arr.length; i++) { v = arr[i]; S }
            
            // make   int $$foreach = 0;   as the initialization
            Name name = names.fromString("$$foreach");
            JCVariableDecl decl = makeVariableDecl(name,syms.intType,makeLiteral(0,pos),pos);
            com.sun.tools.javac.util.List<JCStatement> initList = com.sun.tools.javac.util.List.<JCStatement>of(decl);
            
            // make   $$foreach < <expr>.length   as the loop test
            JCIdent id = (JCIdent)factory.at(pos).Ident(decl);
            id.type = decl.type;
            JCExpression fa = factory.at(pos).Select(that.getExpression(),syms.lengthVar);
            fa.type = syms.intType;
            JCExpression test = makeBinary(JCTree.LT,id,fa,pos);

            // make   $$foreach = $$foreach + 1  as the update list
            JCIdent idd = (JCIdent)factory.at(pos+1).Ident(decl);
            id.type = decl.type;
            JCAssign asg = factory.at(idd.pos).Assign(idd,
                    makeBinary(JCTree.PLUS,id,makeLiteral(1,pos),idd.pos));
            asg.type = syms.intType;
            JCExpressionStatement update = factory.at(idd.pos).Exec(asg);
            com.sun.tools.javac.util.List<JCExpressionStatement> updateList = com.sun.tools.javac.util.List.<JCExpressionStatement>of(update);
            
            // make   <var> = <expr>[$$foreach]    as the initialization of the target and prepend it to the body
            JCArrayAccess aa = factory.at(pos).Indexed(that.getExpression(),id);
            aa.type = that.getVariable().type;
            JCIdent v = (JCIdent)factory.at(pos).Ident(that.getVariable());
            v.type = aa.type;
            asg = factory.at(pos).Assign(v,aa);
            asg.type = v.type;
            ListBuffer<JCStatement> newbody = new ListBuffer<JCStatement>();
            newbody.append(factory.at(pos).Exec(asg));
            newbody.append(that.body);
            visitLoopWithSpecs(that,initList,test,updateList,factory.at(that.body.pos).Block(0,newbody.toList()),loopSpecs);
        } else {
            notImpl(that);
        }
    }
    
    
    public void visitLabelled(JCLabeledStatement that) {
        Map<VarSymbol,Integer> map = new HashMap<VarSymbol,Integer>(currentMap);
        labelmaps.put(that.label,map);
        labelmapStatement.put(that.label,that);
        that.getStatement().accept(this);
    }
    
    public void visitSwitch(JCSwitch that) { 
        int pos = that.pos;
        JCExpression switchExpression = that.selector;
        List<JCCase> cases = that.cases;
        
        // Create the ending block name
        String endBlock = blockPrefix + pos + "$switchEnd";
        
        try {
            breakStack.addFirst(that);

            // We create a new auxiliary variable to hold the switch value, so 
            // we can track its value and so the subexpression does not get
            // replicated.  We add an assumption about its value and add it to
            // list of new variables
            String switchName = ("$switchExpression" + pos 
                    + "$" + that.getStartPosition() + "$" + that.getStartPosition()); // FIXME - use end position
            JCExpression newexpr = trExpr(switchExpression);
            JCIdent vd = newAuxIdent(switchName,switchExpression.type,newexpr.pos);
            JmlTree.JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,factory.Binary(JCTree.EQ,vd,newexpr));
            asm.pos = switchExpression.pos;
            newstatements.add(asm);
            BasicBlock switchStart = currentBlock;

            // Now create an (unprocessed) block for everything that follows the
            // switch statement
            BasicBlock b = newBlock(endBlock,pos,currentBlock);// it gets all the followers of the current block
            List<JCStatement> temp = b.statements;
            b.statements = remainingStatements; // it gets all of the remaining statements
            remainingStatements = temp;
            blocksToDo.add(0,b); // push it on the front of the to do list
            BasicBlock brest = b;

            // Now we need to make blocks, processing them as we go, for all of the case statements
            // Note that there might be nesting of other switch statements etc.
            java.util.LinkedList<BasicBlock> blocks = new java.util.LinkedList<BasicBlock>();
            BasicBlock prev = null;
            JCExpression defaultCond = falseLiteral;
            JmlTree.JmlStatementExpr defaultAsm = null;
            for (JCCase caseStatement: cases) {
                JCExpression caseValue = caseStatement.getExpression();
                List<JCStatement> stats = caseStatement.getStatements();
                String caseName = ("$case" + caseStatement.pos 
                        + "$" + that.getStartPosition() + "$" + that.getStartPosition()); // FIXME - use end position
                BasicBlock blockForTest = newBlock(caseName,caseStatement.pos);
                follows(switchStart,blockForTest);
                JCBinary eq = caseValue == null ? null : factory.Binary(JCTree.EQ,vd,caseValue);
                asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,eq);
                asm.pos = switchExpression.pos;
                blockForTest.statements.add(asm);
                if (caseValue == null) defaultAsm = asm;
                else defaultCond = factory.Binary(JCTree.OR,eq,defaultCond);
                blocks.add(blockForTest);
                boolean fallthrough = stats.isEmpty() || !(stats.get(stats.size()-1) instanceof JCBreak);
                if (prev == null) {
                    // statements can go in the same block
                    blockForTest.statements.addAll(stats);
                    if (!fallthrough) follows(blockForTest,brest);
                    prev = fallthrough ? blockForTest : null;
                } else {
                    // since there is fall-through, the statements need to go in their own block
                    String caseStats = ("$caseStats" + caseStatement.pos 
                            + "$" + that.getStartPosition() + "$" + that.getStartPosition()); // FIXME - use end position
                    BasicBlock blockForStats = newBlock(caseStats,caseStatement.pos);
                    blockForStats.statements.addAll(stats);
                    follows(blockForTest,blockForStats);
                    follows(prev,blockForStats);
                    if (!fallthrough) follows(blockForStats,brest);
                    blocks.add(blockForStats);
                    prev = fallthrough ?  blockForStats : null;
                }
            }
            if (prev != null) follows(prev,brest);
            if (defaultAsm != null) {
                JCExpression eq = factory.Unary(JCTree.NOT,defaultCond);
                defaultAsm.expression = eq;
            }
            // push all of the blocks onto the to do list
            while (!blocks.isEmpty()) {
                blocksToDo.add(0,blocks.removeLast());
            }
        } finally {
            breakStack.removeFirst();
        }
        
        
    }
    
    // Should never get here because case statements are handled in switch
    public void visitCase(JCCase that) { shouldNotBeCalled(that); }
    
    protected java.util.Deque<BasicBlock> tryStack = new java.util.LinkedList<BasicBlock>();

    public void visitTry(JCTry that) {
        // Create an (unprocessed) block for everything that follows the
        // try statement
        BasicBlock b = newBlock(blockPrefix + that.pos + "$afterTry",that.pos,currentBlock);// it gets all the followers of the current block
        List<JCStatement> temp = b.statements;
        b.statements = remainingStatements; // it gets all of the remaining statements
        remainingStatements = temp;
        blocksToDo.add(0,b); // push it on the front of the to do list
        BasicBlock brest = b;
        remainingStatements.add(0,that.getBlock()); // push on the front of the list
        JCExpression e = factory.Binary(JCTree.EQ,terminationVar,zeroLiteral);
        e.type = syms.booleanType;
        JmlTree.JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,e); 
        brest.statements.add(0,asm);
        
        // We make an empty finally block if the try statement does not
        // have one, just to simplify things
        JCBlock finallyStat = that.getFinallyBlock();
        int pos = that.pos;
        String finallyBlockName = blockPrefix + pos + "$finally";
        BasicBlock finallyBlock = newBlock(finallyBlockName,pos,currentBlock);// it gets all the followers of the current block
        if (finallyStat != null) finallyBlock.statements.add(finallyStat); // it gets all of the remaining statements
        blocksToDo.add(0,finallyBlock); // push it on the front of the to do list
        follows(currentBlock,finallyBlock);
        follows(finallyBlock,brest);
        if (tryStack.isEmpty()) {
            follows(finallyBlock,condReturnBlock);
            follows(finallyBlock,condExceptionBlock);
        } else {
            BasicBlock nextFinallyBlock = tryStack.peekFirst();
            follows(finallyBlock,nextFinallyBlock);
        }
        tryStack.addFirst(finallyBlock);

        // FIXME - have to handle exceptions going someplace else
        int i = 0;
        for (JCCatch catcher: that.catchers) {
            String catchBlockName = blockPrefix + catcher.pos + "$catch";
            BasicBlock catchBlock = newBlock(catchBlockName,catcher.pos);
            catchBlock.statements.add(catcher.getBlock()); // it gets all of the remaining statements
            blocksToDo.add(i++,catchBlock); // push it on the front of the to do list
            follows(catchBlock,finallyBlock);
        }

        // Do the processing here
        processBlockStatements(false);
    }
    
    public void visitCatch(JCCatch that) { 
        shouldNotBeCalled(that); 
    }
    
    public void visitConditional(JCConditional that) { 
        JCExpression cond = trExpr(that.cond);
        JCExpression truepart = trExpr(that.truepart);
        JCExpression falsepart = trExpr(that.falsepart);
        JCConditional now = factory.Conditional(cond,truepart,falsepart);
        now.type = that.type;
        now.pos = that.pos;
        result = now;
    }
    
    public void visitIf(JCIf that) {
        int pos = that.pos;
        // Create a bunch of block names
        String thenName = blockPrefix + pos + "$then";
        String elseName = blockPrefix + pos + "$else";
        String restName = blockPrefix + pos + "$afterIf";
        
        // We create a new auxiliary variable to hold the branch condition, so 
        // we can track its value and so the subexpression does not get
        // replicated.  We add an assumption about its value and add it to
        // list of new variables
        String condName = "branchCondition$" + that.getStartPosition();
        JCIdent vd = newAuxIdent(condName,syms.booleanType,that.getStartPosition());
        JCExpression newexpr = makeBinary(JCTree.EQ,vd,that.cond,that.cond.pos);
        JmlTree.JmlStatementExpr asm = factory.at(that.pos).JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,trExpr(newexpr));
        newstatements.add(asm);
        
        // Now create an (unprocessed) block for everything that follows the
        // if statement
        BasicBlock b = newBlock(restName,pos,currentBlock);// it gets all the followers of the current block
        List<JCStatement> temp = b.statements;
        b.statements = remainingStatements; // it gets all of the remaining statements
        remainingStatements = temp;
        blocksToDo.add(0,b); // push it on the front of the to do list
        BasicBlock brest = b;
        
        // Now make the else block, also unprocessed
        b = newBlock(elseName,pos);
        JCExpression c = makeUnary(JCTree.NOT,vd,pos);
        JmlTree.JmlStatementExpr t = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.BRANCHE,c);
        t.pos = that.cond.pos + 1;
        b.statements.add(t);
        if (that.elsepart != null) b.statements.add(that.elsepart);
        blocksToDo.add(0,b);
        follows(b,brest);
        follows(currentBlock,b);
        
        // Now make the then block, also still unprocessed
        b = newBlock(thenName,pos);
        c = vd;
        //c = addAuxVariable("auxT"+that.cond.pos,syms.booleanType,c,true);
        t = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.BRANCHT,c);
        t.pos = that.cond.pos;
        b.statements.add(t);
        b.statements.add(that.thenpart);
        blocksToDo.add(0,b);
        follows(b,brest);
        follows(currentBlock,b);
    }
    
    public void visitExec(JCExpressionStatement that)    { 
        // This includes assignments and stand-alone method invocations
        that.expr.accept(this);
        // ignore the result; any statements are already added
    }
    
    protected java.util.Deque<JCStatement> breakStack = new java.util.LinkedList<JCStatement>();
    
    public void visitBreak(JCBreak that) { 
        if (breakStack.isEmpty()) {
            // ERROR - FIXME
        } else if (breakStack.peekFirst() instanceof JCSwitch) {
            // Don't need to do anything.  If the break is not at the end of a block,
            // the compiler would not have passed this.  If it is at the end of a block
            // the logic in handling JCSwitch has taken care of everything.
            
            // FIXME - for safety, check and warn if there are any remaining statements in the block
        } else {
            // Some kind of loop
        }
    }
    
    public void visitContinue(JCContinue that)           { notImpl(that); }
    
    public void visitReturn(JCReturn that)               {
        if (that.getExpression() != null) {
            JCExpression res = trExpr(that.getExpression());
            JCExpression now = factory.Binary(JCTree.EQ,resultVar,res);
            JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,now);
            newstatements.add(asm);
        }
        JCIdent id = newIdentIncarnation(terminationVar,that.pos);
        JCLiteral lit = factory.at(that.getStartPosition()).Literal(TypeTags.INT,that.getStartPosition());
        lit.type = syms.intType;
        JCExpression e = factory.Binary(JCTree.EQ,id,lit);
        e.type = syms.booleanType;
        JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,e);
        newstatements.add(asm);
        if (tryStack.isEmpty()) {
            replaceFollows(currentBlock,returnBlock);
        } else {
            BasicBlock finallyBlock = tryStack.peekFirst();
            replaceFollows(currentBlock,finallyBlock);
        }
        // FIXME check and warn if there are remaining statements
    }
    
    public void visitThrow(JCThrow that) { 
        
        // Capture the exception expression
        JCExpression res = trExpr(that.getExpression());
        JCExpression now = factory.at(that.pos).Binary(JCTree.EQ,exceptionVar,res);
        now.type = syms.booleanType;
        JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,now);
        newstatements.add(asm);
        
        JCIdent id = newIdentIncarnation(terminationVar,that.pos);
        JCLiteral lit = factory.at(that.getStartPosition()).Literal(TypeTags.INT,-that.getStartPosition());
        lit.type = syms.intType;
        JCExpression expr = factory.Binary(JCTree.EQ,id,lit);
        asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,expr);
        newstatements.add(asm);
        
        if (tryStack.isEmpty()) {
            replaceFollows(currentBlock,exceptionBlock);
        } else {
            BasicBlock finallyBlock = tryStack.peekFirst();
            replaceFollows(currentBlock,finallyBlock);
        }
        // FIXME check and warn if there are remaining statements
    }
    
    public void visitAssert(JCAssert that) { 
        JCExpression cond = trExpr(that.cond);
        JCExpression detail = trExpr(that.detail);
        // FIXME - what to do with detail
        // FIXME - for now turn cond into a JML assertion
        // FIXME - need a label for the assert statement
        // FIXME - set line and source
        addAssert(Label.EXPLICIT_ASSERT, cond, that.cond.getStartPosition(), newstatements, that.cond.getStartPosition());
    }
    
    public void visitApply(JCMethodInvocation that) { 
        if (!(that.meth instanceof JmlFunction)) {
            JCExpression now;
            JCExpression obj;
            MethodSymbol msym;
            if (that.meth instanceof JCIdent) {
                now = trExpr(that.meth);
                msym = (MethodSymbol)((JCIdent)now).sym;
                if (msym.isStatic()) obj = null;
                else obj = thisId;
            } else if (that.meth instanceof JCFieldAccess) {
                JCFieldAccess fa = (JCFieldAccess)that.meth;
                msym = (MethodSymbol)(fa.sym);
                if (msym.isStatic()) obj = null;
                else obj = trExpr( fa.selected );
            } else {
                // FIXME - not implemented
                msym = null;
                obj = null;
            }
        
            // FIXME - what does this translation mean?
            ListBuffer<JCExpression> newtypeargs = new ListBuffer<JCExpression>();
            for (JCExpression arg: that.typeargs) {
                JCExpression n = trExpr(arg);
                newtypeargs.append(n);
            }

            ListBuffer<JCExpression> newargs = new ListBuffer<JCExpression>();
            for (JCExpression arg: that.args) {
                JCExpression n = trExpr(arg);
                newargs.append(n);
            }

            // FIXME - concerned that the position here is not after the
            // positions of all of the arguments
            if (inSpecExpression) {
                result = insertSpecMethodCall(that.pos,msym,obj,newtypeargs.toList(),newargs.toList());
            } else {
                result = insertMethodCall(that.pos,msym,obj,newargs.toList());
            }
            return;
        }
        JmlToken token = ((JmlFunction)that.meth).token;
        
        if (token == JmlToken.BSOLD) {
            Map<VarSymbol,Integer> prev = currentMap;
            currentMap = oldMap;
            try {
                // There is only one argument to translate
                result = trExpr(that.args.get(0));
            } finally {
                currentMap = prev;
            }
            return;
        } else {
            System.out.println("JmlFunction not implemented " + token.internedName());
        }
        return;
    }
    
    /** This is not a tree walker visitor, but just a helper method called when 
     * there is a (pure) method invocation inside a specification expression.
     * The translation here is to keep the call (modified) but at an assumption
     * that implies values for the method.
     * 
     * @param pos
     * @param sym
     * @param obj already translated method receiver, or null if static
     * @param args already translated method arguments
     * @returns
     */
    public JCExpression insertSpecMethodCall(int pos, MethodSymbol sym, JCExpression obj, com.sun.tools.javac.util.List<JCExpression> typeargs, com.sun.tools.javac.util.List<JCExpression> args) {
        Map<VarSymbol,Integer> prevOldMap = oldMap;
        JCIdent prevThisId = thisId;
        JCExpression prevResultVar = resultVar;
        
        try {
            JmlMethodSpecs mspecs = specs.getSpecs(sym);
            JCExpression newapply = null;
            if (mspecs == null) {
                System.out.println("NO SPECS FOR METHOD CALL");
            } else {
                JmlMethodDecl decl = mspecs.decl;
                JmlMethodInfo mi = getMethodInfo(sym);
                JCIdent newMethodName = newAuxIdent(encodedName(sym,decl.pos,pos).toString(),sym.type,pos); // FIXME - string to name to string to name
                
                if (obj == null && args.size() == 0) {
                    // Static and no arguments, so we just use the new method name
                    // as a constant
                    newapply = newMethodName;
                    resultVar = newMethodName; // FIXME - what about typeargs
                    for (JCExpression post: mi.ensuresPredicates) {
                        JCExpression expr = trExpr(post);
                        addAssume(Label.METHODAXIOM,expr,newstatements,false);
                    }
                } else {
                    // Construct what we are going to replace \result with
                    ListBuffer<JCExpression> newargs = new ListBuffer<JCExpression>();
                    if (obj != null) newargs.append(obj);
                    for (JCExpression e: args) newargs.append(e);
                    newapply = factory.at(pos).Apply(typeargs,newMethodName,newargs.toList());

                    // Construct what we are going to replace \result with
                    ListBuffer<JCExpression> margs = new ListBuffer<JCExpression>();
                    ListBuffer<Name> fparams = new ListBuffer<Name>();
                    ListBuffer<JCExpression> localtypes = new ListBuffer<JCExpression>();
                    
                    if (obj != null) {
                        margs.append(thisId);
                        fparams.append(thisId.name);
                        localtypes.append(factory.Type(syms.objectType));
                    }
                    for (JCVariableDecl e: decl.params) {
                        JCIdent p = newIdentUse(e.sym,0);
                        margs.append(p);
                        fparams.append(p.name);
                        localtypes.append(e.vartype);
                    }
                    JCExpression mapply = factory.at(pos).Apply(null,newMethodName,margs.toList()); // FIXME - what about typeargs

                    ListBuffer<Name> single = new ListBuffer<Name>();
                    single.append(thisId.name);
                    resultVar = mapply;
                    for (JCExpression post: mi.ensuresPredicates) {
                        JCExpression predicate = trExpr(post);
                        JmlQuantifiedExpr expr = factory.at(pos).JmlQuantifiedExpr(
                                JmlToken.BSFORALL, null, localtypes,
                                fparams, null, predicate);
                        addAssume(Label.METHODAXIOM,expr,newstatements,false);
                    }

                }
            }
            return newapply;
        } finally {
            oldMap = prevOldMap;
            thisId = prevThisId;
            resultVar = prevResultVar;
        }
    }
        
    protected JCIdent insertMethodCall(int pos, MethodSymbol sym, JCExpression obj, List<JCExpression> args) {
        Map<VarSymbol,Integer> prevOldMap = oldMap;
        JCIdent prevThisId = thisId;
        JCIdent retId = sym.type == null ? null : newAuxIdent("$$result$"+pos,sym.type,pos);
        JCExpression prevResultVar = resultVar;
        
        try {
            JmlMethodSpecs mspecs = specs.getSpecs(sym);
            if (mspecs == null) {
                System.out.println("NO SPECS FOR METHOD CALL");
            } else {
                
                JCExpression expr;
                // Note: need to do all of the expression translation before
                // we assign the new thisId
                
                // Evaluate all of the arguments and assign them to a new variable
                JmlMethodDecl decl = mspecs.decl;
                int i = 0;
                for (JCVariableDecl vd  : decl.params) {
                    expr = args.get(i++);
                    JCIdent id = newIdentIncarnation(vd,pos);
                    // FIXME - end information?  use copyInfo?
                    expr = factory.at(id.pos).Binary(JCTree.EQ,id,expr);
                    expr.type = syms.booleanType; // FIXME - start end? source line?
                    JCStatement st = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,expr);
                    newstatements.add(st);
                }
                
                thisId = newAuxIdent("this$"+pos,syms.objectType,pos); // FIXME - object type?
                if (obj != null) {
                    expr = factory.at(obj.pos).Binary(JCTree.EQ,thisId,obj);
                    expr.type = syms.booleanType; // FIXME - start end? source line?
                    JCStatement st = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,expr);
                    newstatements.add(st);
                }

                boolean isStaticCalled = sym.isStatic();
                boolean isConstructorCalled = sym.isConstructor();
                boolean isHelperCalled = isHelper(sym);
                JmlClassInfo calledClassInfo = getClassInfo(sym.owner);
                if (isConstructorCalled) {
                    // Presuming that isConstructor
                    // We are calling a this or super constructor
                    // static invariants have to hold
                    if (!isHelperCalled) {
                        for (JmlTypeClauseExpr inv : calledClassInfo.staticinvariants) {
                            JCExpression e = inv.expression;
                            e = trExpr(e);
                            addAssert(Label.INVARIANT,e,inv.getStartPosition(),newstatements,pos);
                        }
                    }
                } else if (!isConstructor && !isHelper) {
                    for (JmlTypeClauseExpr inv : classInfo.staticinvariants) {
                        JCExpression e = inv.expression;
                        e = trExpr(e);
                        addAssert(Label.INVARIANT,e,inv.getStartPosition(),newstatements,pos);
                    }
                    if (!isStatic) {
                        for (JmlTypeClauseExpr inv : classInfo.invariants) {
                            JCExpression e = inv.expression;
                            e = trExpr(e);
                            addAssert(Label.INVARIANT,e,inv.getStartPosition(),newstatements,pos);
                        }
                    }
                }
                JCExpression exprr = null;
                JmlMethodInfo mi = getMethodInfo(sym);
                if (mi.requiresPredicates.size()==0) exprr = makeLiteral(true,mi.decl.pos);
                else for (JCExpression pre: mi.requiresPredicates) {
                    pre = trExpr(pre);
                    if (exprr == null) exprr = pre;
                    else {
                        exprr = factory.at(exprr.pos).Binary(JCTree.OR,exprr,pre);
                        exprr.type = syms.booleanType;
                    }
                }
                
                if (!isConstructorCalled && !isStaticCalled) {
                    MethodSymbol msym = sym;
                    while ((msym=getOverrided(msym)) != null) {
                        exprr = addMethodPreconditions(currentBlock,msym,decl,decl.pos,exprr);
                    }
                }
                addAssert(Label.PRECONDITION,exprr,exprr.getStartPosition(),newstatements,pos);


                oldMap = new HashMap<VarSymbol,Integer>(currentMap);

                for (JmlMethodInfo.Entry entry: mi.assignables) {
                    // What to do with preconditions?  FIXME
                            for (JCTree sr: entry.storerefs) {
                                if (sr instanceof JCIdent) {
                                    JCIdent id = (JCIdent)sr;
                                    newIdentIncarnation(id,pos+1); // new incarnation
                                } else {
                                    System.out.println("UNIMPLEMENTED STORE REF " + sr.getClass());
                                }
                            }
                }
                
                resultVar = retId;
                for (JCExpression post: mi.ensuresPredicates) {
                    addAssume(Label.POSTCONDITION,trExpr(post),newstatements,false);
                }
                if (!isConstructorCalled && !isStaticCalled) {
                    MethodSymbol msym = sym;
                    while ((msym=getOverrided(msym)) != null) {
                        mi = getMethodInfo(msym);
                        for (JCExpression post: mi.ensuresPredicates) {
                            addParameterMappings(mspecs.decl,mi.decl,pos,currentBlock);
                            addAssume(Label.POSTCONDITION,trExpr(post),newstatements,false);
                        }
                    }
                }

                resultVar = prevResultVar;
                
                if (isConstructorCalled) {
                    // Presuming that isConstructor
                    // Calling a super or this constructor
                    if (!isHelperCalled) {
                        for (JmlTypeClauseExpr inv : calledClassInfo.staticinvariants) {
                            JCExpression e = inv.expression;
                            e = trExpr(e);
                            addAssume(Label.INVARIANT,e,newstatements,false);
                        }
                        for (JmlTypeClauseExpr inv : calledClassInfo.invariants) {
                            JCExpression e = inv.expression;
                            e = trExpr(e);
                            addAssume(Label.INVARIANT,e,newstatements,false);
                        }
                        for (JmlTypeClauseConstraint inv : calledClassInfo.staticconstraints) {
                            JCExpression e = inv.expression;
                            e = trExpr(e);
                            addAssume(Label.CONSTRAINT,e,newstatements,false);
                        }
                    }
                } else if (!isHelper) {
                    for (JmlTypeClauseExpr inv : classInfo.staticinvariants) {
                        JCExpression e = inv.expression;
                        e = trExpr(e);
                        addAssume(Label.INVARIANT,e,newstatements,false);
                    }
                    if (!isStatic) {
                        for (JmlTypeClauseExpr inv : classInfo.invariants) {
                            JCExpression e = inv.expression;
                            e = trExpr(e);
                            addAssume(Label.INVARIANT,e,newstatements,false);
                        }
                    }
                    for (JmlTypeClauseConstraint inv : classInfo.staticconstraints) {
                        JCExpression e = inv.expression;
                        e = trExpr(e);
                        addAssume(Label.CONSTRAINT,e,newstatements,false);
                    }
                    if (!isConstructor) {
                        if (!isStatic) {
                            for (JmlTypeClauseConstraint inv : classInfo.constraints) {
                                JCExpression e = inv.expression;
                                e = trExpr(e);
                                addAssume(Label.CONSTRAINT,e,newstatements,false);
                            }
                        }
                    }
                }
                // Take out the temporary variables for the arguments
                for (JCVariableDecl vd  : decl.params) {
                    currentMap.remove((VarSymbol)vd.sym);
                }
            }
        } finally {
            oldMap = prevOldMap;
            thisId = prevThisId;
            resultVar = prevResultVar;
            result = retId;
        }
        return retId;
    }
    
    public void visitSkip(JCSkip that)                   {
        // do nothing
    }
    public void visitJmlStatement(JmlStatement that) {
        // Just do all the JML statements as if they were Java statements, 
        // since they are part of specifications
        that.statement.accept(this);
    }
    
    public void visitJmlStatementLoop(JmlStatementLoop that)       { notImpl(that); }
    public void visitJmlStatementSpec(JmlStatementSpec that)       { notImpl(that); }
    
    public void visitJmlStatementExpr(JmlStatementExpr that) { 
        JmlStatementExpr now;
        if (that.token == JmlToken.ASSUME || that.token == JmlToken.ASSERT) {
            JCExpression expr = trSpecExpr(that.expression);
            JCExpression opt = trSpecExpr(that.optionalExpression);
            if (that.token == JmlToken.ASSERT) {
                expr = addAssert(that.label,expr,that.getStartPosition(),newstatements,that.pos);
            }
            if (that.token == JmlToken.ASSUME) {
                if (that.label == Label.EXPLICIT_ASSUME || that.label == Label.BRANCHT || that.label == Label.BRANCHE) {
                    now = factory.at(that.pos).JmlExpressionStatement(that.token,that.label,expr);
                    now.optionalExpression = opt;
                    now.type = that.type;   
                    currentBlock.statements.add(now);

                    JCExpression id = newAuxIdent("checkAssumption$" + that.label + "$" + that.pos, syms.booleanType, that.pos);
                    now = factory.at(that.pos).JmlExpressionStatement(JmlToken.ASSERT,Label.EXPLICIT_ASSUME,id);
                    now.optionalExpression = null;
                    now.type = that.type;   
                    //currentBlock.statements.add(now);
                    newdefs.add(factory.at(that.pos).Binary(JCTree.EQ,id,trueLiteral));
                } else {
                    now = factory.JmlExpressionStatement(that.token,that.label,expr);
                    now.optionalExpression = opt;
                    now.pos = that.pos;
                    now.type = that.type;   
                }
            } else {
                now = factory.JmlExpressionStatement(that.token,that.label,expr);
                now.optionalExpression = opt;
                now.pos = that.pos;
                now.type = that.type;
            }
            currentBlock.statements.add(now);
            if (that.token == JmlToken.ASSUME && (that.label == Label.EXPLICIT_ASSUME 
                    || that.label == Label.BRANCHT || that.label == Label.BRANCHE)) {
                int pos = now.pos;
                String n = "assumeCheck$" + that.pos + "$" + that.label.toString();
                JCExpression count = factory.Literal(TypeTags.INT,that.pos);
                JCExpression e = factory.at(pos).Binary(JCTree.NE,assumeCheckCountVar,count);
                JCExpression id = newAuxIdent(n,syms.booleanType,e.pos);
                e = factory.at(pos).JmlBinary(JmlToken.EQUIVALENCE,id,e);
                JmlStatementExpr st = factory.at(pos).JmlExpressionStatement(JmlToken.ASSUME,Label.ASSUME_CHECK,e);
                newstatements.add(st);
                // an assert without tracking
                st = factory.at(that.pos).JmlExpressionStatement(JmlToken.ASSERT,Label.ASSUME_CHECK,id);
                // FIXME - start and end?
                st.optionalExpression = null;
                st.type = null; // FIXME - is this right?
                // FIXME - what about source and line?
                newstatements.add(st);
            }

        } else if (that.token == JmlToken.UNREACHABLE) {
            JCExpression expr = factory.Literal(TypeTags.BOOLEAN,Boolean.FALSE);
            expr.pos = that.pos; // FIXME - start and end position?
            addAssert(Label.UNREACHABLE,expr,that.getStartPosition(),currentBlock.statements,that.getStartPosition());
        } else {
            // ERROR - FIXME
        }
    }
    public void visitJmlStatementDecls(JmlStatementDecls that)     { notImpl(that); }
    public void visitJmlDoWhileLoop(JmlDoWhileLoop that)           { notImpl(that); }

    // Expression nodes to be implemented
    
    public void visitParens(JCParens that) { 
        JCExpression expr = trExpr(that.expr);
        JCParens now = factory.Parens(expr);
        now.type = that.type;
        now.pos = that.pos;
        result = now;
    }
    
    public void visitUnary(JCUnary that)                 { 
        JCExpression arg = trExpr(that.arg);
        int tag = that.getTag();
        if (tag == JCTree.POSTDEC || tag == JCTree.POSTINC ||
                tag == JCTree.PREDEC || tag == JCTree.PREINC) {
            int op = tag == JCTree.PREDEC || tag == JCTree.POSTDEC ?
                    JCTree.MINUS : JCTree.PLUS;
            JCExpression e = factory.at(that.pos).Binary(op,arg,factory.at(that.pos).Literal(TypeTags.INT,1));
            result = doAssignment(arg,e,that.pos);
            if (tag == JCTree.POSTDEC || tag == JCTree.POSTINC) result = arg;
            return;
        }
        if (arg == that.arg) { result = that; return; }
        JCUnary now = factory.Unary(that.getTag(),arg);
        now.operator = that.operator;
        now.type = that.type;
        now.pos = that.pos;
        result = now;
    }
    
    public void visitBinary(JCBinary that) { 
        JCExpression left = trExpr(that.lhs);
        JCExpression right = trExpr(that.rhs);
        JCBinary now = factory.Binary(that.getTag(),left,right);
        now.operator = that.operator;
        now.type = that.type;
        now.pos = that.pos;
        result = now;
    }
    
    public void visitTypeCast(JCTypeCast that)           { notImpl(that); }
    public void visitTypeTest(JCInstanceOf that)         { notImpl(that); }
    
    public void visitIndexed(JCArrayAccess that) { 
        JCExpression array = trExpr(that.getExpression());
        JCExpression index = trExpr(that.getIndex());
        JCIdent arrayID = getArrayIdent(that.type);
        JCArrayAccess now = new JmlBBArrayAccess(arrayID,array,index);
        now.pos = that.pos;
        now.type = that.type;
        result = now;
    }
    
    public void visitSelect(JCFieldAccess that) {
        Symbol sym = that.sym;
        if (sym == null) {
            System.out.println("NULL SYM IN SELECT: " + that.name); // FIXME
        } else if (sym.isStatic()) {
            VarSymbol vsym = (VarSymbol)that.sym;
            JCIdent now = newIdentUse(vsym,that.pos);
            now.type = that.type;
            result = now;
        } else if (sym instanceof VarSymbol){
            JCExpression selected = trExpr(that.selected);
            JCIdent id = newIdentUse((VarSymbol)sym,that.pos);
            JCFieldAccess now = new JmlBBFieldAccess(id,selected);
            now.pos = that.pos;
            now.type = that.type;
            result = now;
        } else if (sym instanceof MethodSymbol) {
            // FIXME - should not get here
        } else {
            // FIXME - don't know what this could be
        }
    }
    
    public void visitIdent(JCIdent that) { 
        if (that.sym instanceof VarSymbol) {
            VarSymbol vsym = (VarSymbol)that.sym;
            Symbol owner = that.sym.owner;
            if (owner != null && owner instanceof ClassSymbol && !vsym.isStatic() &&
                    !vsym.toString().equals("this")) {
                // This is a field reference without the default this. prefix
                // We need to make it a JCFieldAccess with a 'this'
                
                // FIXME - is the symbol for 'this' stored somewhere, or can
                // we get it by a lookup (so we're sure to have all the correct
                // type and symbol information)?  or at least do all of the following
                // computations just once
                JCIdent thisId = factory.Ident(names.fromString("this"));
                thisId.pos = that.pos;
                VarSymbol v = new VarSymbol(0,thisId.name,owner.type,owner);
                v.pos = 0;
                thisId.sym = v;
                thisId.type = owner.type;
                JCFieldAccess now = factory.Select(thisId,vsym.name);
                now.pos = that.pos;
                now.type = that.type;
                now.sym = vsym;
                result = trExpr(now);
            } else if (vsym.toString().equals("this")) {
                result = thisId;
            } else {
                result = newIdentUse(vsym,that.pos);
            }
        } else {
            result = that;
        }
    }
    
    public void visitLiteral(JCLiteral that) { 
        // numeric, boolean, character, String, null
        // FIXME - not sure about characters or String or class literals
        result = that;
    }
    
    public void visitAssign(JCAssign that) { 
        JCExpression left = trExpr(that.lhs);
        JCExpression right = trExpr(that.rhs);
        result = doAssignment(left,right,that.pos);
    }
    
    // FIXME - embedded assignments to array elements are not implemented; no warning either
    
    protected JCExpression doAssignment(JCExpression left, JCExpression right, int pos) {
        if (left instanceof JCIdent) {
            JCIdent id = (JCIdent)left;
            left = newIdentIncarnation(id,pos);
            JCBinary expr = factory.at(pos).Binary(JCBinary.EQ,left,right);
            expr.type = syms.booleanType;

            // FIXME - set line and source
            JmlStatementExpr assume = factory.at(pos).JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,expr);
            newstatements.add(assume); 
            return left;
        } else if (left instanceof JCArrayAccess) {
            JCIdent arr = getArrayIdent(right.type);
            JCExpression ex = ((JCArrayAccess)left).indexed;
            JCIdent nid = newArrayIncarnation(right.type,pos);
            JCExpression expr = new JmlBBArrayAssignment(nid,arr,ex,((JCArrayAccess)left).index,right);
            expr.pos = pos;
            expr.type = syms.booleanType;

            // FIXME - set line and source
            JmlStatementExpr assume = factory.at(pos).JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,expr);
            newstatements.add(assume); 
            return left;
        } else if (left instanceof JCFieldAccess) {
            JCFieldAccess fa = (JCFieldAccess)left;
            Name name = fa.name;
            JCIdent oldfield = newIdentUse((VarSymbol)fa.sym,pos);
            JCIdent newfield = newIdentIncarnation(oldfield,pos);
            JCExpression expr = new JmlBBFieldAssignment(newfield,oldfield,fa.selected,right);
            expr.pos = pos;
            expr.type = syms.booleanType;

            // FIXME - set line and source
            JmlStatementExpr assume = factory.at(pos).JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,expr);
            newstatements.add(assume); 
            return left;
        } else {
            System.out.println("INCARNATION NOT IMPLERMENTED - visitAssign");
            return null;
        }
    }
    
    // += -= *= /= %= >>= <<=  >>>= &= |= ^=
    public void visitAssignop(JCAssignOp that) { 
        JCExpression left = trExpr(that.lhs);
        JCExpression right = trExpr(that.rhs);
        int op = that.getTag() - JCTree.ASGOffset;
        JCExpression e = makeBinary(op,left,right,that.pos);
        e.type = that.type;  // FIXME is this needed - check all types
        result = doAssignment(left,e,that.pos);
    }


    // TBD
    public void visitVarDef(JCVariableDecl that)         { 
        JCIdent lhs = newIdentIncarnation(that,that.getPreferredPosition());
        if (that.init != null) {
            // Create and store the new lhs incarnation before translating the
            // initializer because the initializer is in the scope of the newly
            // declared variable.  // FIXME - test this
            JCExpression init = trExpr(that.init);
            JCBinary expr = factory.at(that.pos).Binary(JCBinary.EQ,lhs,init);
            expr.type = syms.booleanType;
            // FIXME - set line and source
            JmlStatementExpr assume = factory.at(that.pos).JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,expr);
                // also source and line?  FIXME
            newstatements.add(assume);       
        }
    }
    public void visitSynchronized(JCSynchronized that)   { 
        // FIXME - for now ignore any synchronization
        trExpr(that.getExpression());  // just in case there are side-effects
        that.body.accept(this);
    }
    
    public void visitNewClass(JCNewClass that) {
        // FIXME - ignoring enclosing expressions; ignoring anonymous classes
        
        boolean isHelper = false;
        JmlMethodInfo mi = null;
        JmlMethodDecl decl = null;
        
        // This is the id of a new variable that represents the result of the
        // new operation.
        JCIdent id = newAuxIdent("$$new"+that.pos+"$",that.type,that.pos);
        
        Symbol.MethodSymbol sym = (MethodSymbol)that.constructor;
        JmlMethodSpecs mspecs = specs.getSpecs(sym);
        if (mspecs == null) {
            System.out.println("NO SPECS FOR METHOD CALL");
        } 
        int pos = that.pos;

        if (mspecs != null) {
            // Evaluate all of the arguments and assign them to new variables
            decl = mspecs.decl;
            int i = 0;
            for (JCVariableDecl vd  : decl.params) {
                JCExpression expr = that.args.get(i++);
                JCIdent pid = newIdentIncarnation(vd,pos);
                // FIXME - end information?  use copyInfo?
                expr = factory.at(pid.pos).Binary(JCTree.EQ,pid,expr);
                expr.type = syms.booleanType; // FIXME - start end? source line?
                JCStatement st = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,expr);
                newstatements.add(st);
            }
            
            // FIXME - observed that for the Object() constructor sym != mspecs.decl.sym ?????

            isHelper = isHelper(sym);
            mi = getMethodInfo(sym);
            for (JCExpression pre: mi.requiresPredicates) {
                addAssert(Label.PRECONDITION,trExpr(pre),decl.pos,newstatements,pos);
            }
        }


        // Save the current incarnation map, so that instances of \old in the
        // postconditions of the called method are mapped to values just before
        // the havoc of assigned variables (and not to the values at the beginning
        // of the method being translated).
        HashMap<VarSymbol,Integer> currentCopy = new HashMap<VarSymbol,Integer>();
        currentCopy.putAll(currentMap);
        Map<VarSymbol,Integer> prevOldMap = oldMap;
        oldMap = currentCopy;

        if (mspecs != null) {

            // Now make a new incarnation value for anything in the assignables list,
            // effectively making its value something legal but undefined.
            for (JmlMethodInfo.Entry entry: mi.assignables) {
                // What to do with preconditions?  FIXME
                for (JCTree sr: entry.storerefs) {
                    if (sr instanceof JCIdent) {
                        JCIdent pid = (JCIdent)sr;
                        newIdentIncarnation(pid,pos+1); // new incarnation
                    } else {
                        System.out.println("UNIMPLEMENTED STORE REF " + sr.getClass());
                    }
                }
            }
        }

        JCIdent oldalloc = newIdentUse((VarSymbol)allocVar.sym,pos);
        JCIdent alloc = newIdentIncarnation(allocVar,pos);
        
        // assume <oldalloc> < <newalloc>
        JCExpression ee = makeBinary(JCTree.LT,oldalloc,alloc,pos);
        addAssume(Label.SYN,ee,newstatements,false);
        
        // assume <newid>.alloc = <newalloc>
        ee = new JmlBBFieldAccess(allocIdent,id);
        ee.type = syms.intType;
        ee = makeBinary(JCTree.EQ,ee,alloc,pos);
        addAssume(Label.SYN,ee,newstatements,false);
        
        // assume <newid> != null;
        ee = makeBinary(JCTree.NE,id,nullLiteral,pos);
        addAssume(Label.SYN,ee,newstatements,false);

        if (mspecs != null) {
            for (JCExpression post: mi.ensuresPredicates) {
                addAssume(Label.POSTCONDITION,trExpr(post),newstatements,false);
            }
            if (!isHelper) {
                for (JmlTypeClauseExpr inv : classInfo.staticinvariants) {
                    JCExpression e = inv.expression;
                    e = trExpr(e);
                    addAssume(Label.INVARIANT,e,newstatements,false);
                }
                for (JmlTypeClauseExpr inv : classInfo.invariants) {
                    JCExpression e = inv.expression;
                    e = trExpr(e);
                    addAssume(Label.INVARIANT,e,newstatements,false);
                }
            }
            // Take out the temporary variables for the arguments
            for (JCVariableDecl vd  : decl.params) {
                currentMap.remove((VarSymbol)vd.sym);
            }
        }
        oldMap = prevOldMap;
        result = id;
    }
    
    public void visitNewArray(JCNewArray that) { //that.dims, elems, elemtype
        JCIdent oldalloc = newIdentUse((VarSymbol)allocVar.sym,that.pos);
        JCIdent alloc = newIdentIncarnation(allocVar,that.pos);
        JCExpression e = factory.at(that.pos).Binary(JCTree.LT,oldalloc,alloc);
        addAssume(Label.SYN,e,newstatements,false);
        
        JCIdent id = newAuxIdent("$$newarray"+that.pos+"$",that.type,that.pos);
        e = new JmlBBFieldAccess(allocIdent,id);
        e.type = syms.intType;
        e = factory.at(that.pos).Binary(JCTree.EQ,e,alloc);
        addAssume(Label.SYN,e,newstatements,false);
        
        List<JCExpression> dims = that.dims;
        
        JCExpression len;
        if (dims.size() != 0) {
            len = that.dims.get(0);
        } else {
            len = factory.Literal(TypeTags.INT,that.elems.size());
            len.type = syms.intType;
        }
            // FIXME - only handling one-D arrays // FIXME - how to encode element types
        e = new JmlBBFieldAccess(lengthIdent,id);
        e.type = syms.intType;
        e = factory.at(that.pos).Binary(JCTree.EQ,e,trExpr(len));
        addAssume(Label.SYN,e,newstatements,false);
        
        if (that.elems != null) {
                int i = 0;
                for (JCExpression ee: that.elems) {
                    JCLiteral lit = factory.at(ee.pos).Literal(TypeTags.INT,i++);
                    lit.type = syms.intType;
                    e = new JmlBBArrayAccess(getArrayIdent(ee.type),id,lit); // FIXME - fix the type
                    e.type = ee.type; // FIXME - actually this is the type of the initializer, which may need conversion
                    e = factory.at(ee.pos).Binary(JCTree.EQ,e,trExpr(ee));
                    e.type = syms.booleanType;
                    addAssume(Label.SYN,e,newstatements,false);
                }
        }
        
        result = id;
    }
    
    public void visitTypeIdent(JCPrimitiveTypeTree that) { notImpl(that); }
    public void visitTypeArray(JCArrayTypeTree that)     { notImpl(that); }
    public void visitTypeApply(JCTypeApply that)         { notImpl(that); }
    public void visitTypeParameter(JCTypeParameter that) { notImpl(that); }
    public void visitWildcard(JCWildcard that)           { notImpl(that); }
    public void visitTypeBoundKind(TypeBoundKind that)   { notImpl(that); }
    public void visitAnnotation(JCAnnotation that)       { notImpl(that); }
    public void visitModifiers(JCModifiers that)         { notImpl(that); }
    public void visitErroneous(JCErroneous that)         { notImpl(that); }
    public void visitLetExpr(LetExpr that)               { notImpl(that); }
    
    public void visitJmlVariableDecl(JmlVariableDecl that) {
        JCIdent vd = newIdentIncarnation(that,that.pos);
        if (that.init != null) {
            JCExpression init = trExpr(that.init);
            JmlTree.JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.ASSIGNMENT,factory.at(that.init.pos).Binary(JCTree.EQ,vd,init));
            asm.pos = that.init.pos;
            newstatements.add(asm);
            // FIXME - check all new asserts and assumes for location info
        }
    }
    
    public void visitJmlSingleton(JmlSingleton that) { 
        switch (that.token) {
            case BSRESULT:
                if (resultVar == null) {
                    throw new RuntimeException(); // FIXME - do something more informative - should not ever get here
                } else {
                    result = resultVar;
                }
                break;

            case INFORMAL_COMMENT:
                result = trueLiteral;
                break;
                
            case BSLOCKSET:
            case BSSAME:
            case BSNOTSPECIFIED:
            case BSNOTHING:
            case BSEVERYTHING:
                Log.instance(context).error(that.pos, "jml.unimplemented.construct",that.token.internedName(),"JmlRac.visitJmlSingleton");
                // FIXME - recovery possible?
                break;

            default:
                Log.instance(context).error(that.pos, "jml.unknown.construct",that.token.internedName(),"JmlRac.visitJmlSingleton");
            // FIXME - recovery possible?
                break;
        }
        //result = that; // TODO - can all of these be present in a basic block?
    }
    
    public void visitJmlFunction(JmlFunction that) {
        switch (that.token) {
            case BSOLD :
            case BSPRE :
                // Handling of incarnation occurs later
                result = that; 
                break;
                
            case BSTYPEOF :
            case BSELEMTYPE :
            case BSNONNULLELEMENTS :
            case BSMAX :
            case BSFRESH :
            case BSREACH :
            case BSSPACE :
            case BSWORKINGSPACE :
            case BSDURATION :
            case BSISINITIALIZED :
            case BSINVARIANTFOR :
            case BSNOWARN:
            case BSNOWARNOP:
            case BSWARN:
            case BSWARNOP:
            case BSBIGINT_MATH:
            case BSSAFEMATH:
            case BSJAVAMATH:
            case BSNOTMODIFIED:
            case BSTYPELC:
                Log.instance(context).error("esc.not implemented","Not yet implemented token in BasicBlocker: " + that.token.internedName());
                result = trueLiteral; // FIXME - may not even be a boolean typed result
                break;

            default:
                Log.instance(context).error("esc.internal.error","Unknown token in BasicBlocker: " + that.token.internedName());
                result = trueLiteral; // FIXME - may not even be a boolean typed result
        }


        
    }

    public void visitJmlBinary(JmlBinary that) { 
        that.lhs.accept(this);
        JCExpression left = result;
        that.rhs.accept(this);
        JCExpression right = result;
        if (left == that.lhs && right == that.rhs) { result = that; return; }
        JmlBinary now = factory.JmlBinary(that.op,left,right); // FIXME - end position infor for all new nodes?
        now.op = that.op;
        now.type = that.type;
        now.pos = that.pos;
        result = now;
    }
    
    public void visitJmlQuantifiedExpr(JmlQuantifiedExpr that)     { notImpl(that); }
    public void visitJmlSetComprehension(JmlSetComprehension that) { notImpl(that); }
    
    public void visitJmlLblExpression(JmlLblExpression that) {
        String n = "$$" + that.token.toString().substring(2) + "$" + that.pos + "$" + that.label;
        JCIdent id = newAuxIdent(n,that.type,that.pos);
        JCExpression e = factory.at(that.pos).Binary(JCTree.EQ,id,trExpr(that.expression));
        e.type = syms.booleanType;
        JmlStatementExpr asm = factory.JmlExpressionStatement(JmlToken.ASSUME,Label.SYN,e);
        newstatements.add(asm);
        result = id;
    }

    
    public void visitJmlStoreRefListExpression(JmlStoreRefListExpression that){ notImpl(that); }
    public void visitJmlGroupName(JmlGroupName that)               { notImpl(that); }
    public void visitJmlTypeClauseIn(JmlTypeClauseIn that)         { notImpl(that); }
    public void visitJmlTypeClauseMaps(JmlTypeClauseMaps that)     { notImpl(that); }
    public void visitJmlTypeClauseExpr(JmlTypeClauseExpr that)     { notImpl(that); }
    public void visitJmlTypeClauseDecl(JmlTypeClauseDecl that)     { notImpl(that); }
    public void visitJmlTypeClauseInitializer(JmlTypeClauseInitializer that) { notImpl(that); }
    public void visitJmlTypeClauseConstraint(JmlTypeClauseConstraint that) { notImpl(that); }
    public void visitJmlTypeClauseRepresents(JmlTypeClauseRepresents that) { notImpl(that); }
    public void visitJmlTypeClauseConditional(JmlTypeClauseConditional that) { notImpl(that); }
    public void visitJmlTypeClauseMonitorsFor(JmlTypeClauseMonitorsFor that) { notImpl(that); }
    public void visitJmlMethodClauseGroup(JmlMethodClauseGroup that) { notImpl(that); }
    public void visitJmlMethodClauseDecl(JmlMethodClauseDecl that) { notImpl(that); }
    public void visitJmlMethodClauseExpr(JmlMethodClauseExpr that) { notImpl(that); }
    public void visitJmlMethodClauseConditional(JmlMethodClauseConditional that) { notImpl(that); }
    public void visitJmlMethodClauseSignals(JmlMethodClauseSignals that) { notImpl(that); }
    public void visitJmlMethodClauseSigOnly(JmlMethodClauseSigOnly that) { notImpl(that); }
    public void visitJmlMethodClauseAssignable(JmlMethodClauseAssignable that) { notImpl(that); }
    public void visitJmlSpecificationCase(JmlSpecificationCase that){ notImpl(that); }
    public void visitJmlMethodSpecs(JmlMethodSpecs that)           {  } // FIXME - IGNORE NOT SURE WHY THIS IS ENCOUNTERED IN CLASS.defs
    public void visitJmlPrimitiveTypeTree(JmlPrimitiveTypeTree that){ notImpl(that); }
    public void visitJmlStoreRefKeyword(JmlStoreRefKeyword that)   { notImpl(that); }
    public void visitJmlStoreRefArrayRange(JmlStoreRefArrayRange that){ notImpl(that); }

    // These do not need to be implemented
    public void visitTopLevel(JCCompilationUnit that)    { shouldNotBeCalled(that); }
    public void visitImport(JCImport that)               { shouldNotBeCalled(that); }
    public void visitJmlCompilationUnit(JmlCompilationUnit that)   { shouldNotBeCalled(that); }
    public void visitJmlRefines(JmlRefines that)                   { shouldNotBeCalled(that); }
    public void visitJmlImport(JmlImport that)                     { shouldNotBeCalled(that); }

    public void visitClassDef(JCClassDecl that) {
        System.out.println("YES THIS IS CALLED - visitClassDef");
//        scan(tree.mods);
//        scan(tree.typarams);
//        scan(tree.extending);
//        scan(tree.implementing);
        scan(that.defs); // FIXME - is this ever called for top level class; is it correct for a class definition statement?
    }
    
    @Override
    public void visitMethodDef(JCMethodDecl that)        { notImpl(that); }
    
    //public void visitJmlClassDecl(JmlClassDecl that) ; // OK to inherit - FIXME - when called?
 
    @Override
    public void visitJmlMethodDecl(JmlMethodDecl that) {
        System.out.println("YES THIS IS CALLED - visitJMLMethodDecl");
        //convertMethodBody(that.body); // FIXME - do the proof?? // Is it ever called? in local classes?
    }
    

    // FIXME - this will go away
    public static class VarFinder extends JmlTreeScanner {
        
        private Set<JCIdent> vars; // FIXME - change to a collection?
        
        public VarFinder() {}
        
        public static Set<JCIdent> findVars(List<? extends JCTree> that, Set<JCIdent> v) {
            VarFinder vf = new VarFinder();
            return vf.find(that,v);
        }
        
        public static Set<JCIdent> findVars(JCTree that, Set<JCIdent> v) {
            VarFinder vf = new VarFinder();
            return vf.find(that,v);
        }
        
        public Set<JCIdent> find(List<? extends JCTree> that, Set<JCIdent> v) {
            if (v == null) vars = new HashSet<JCIdent>();
            else vars = v;
            for (JCTree t: that) t.accept(this);
            return vars;
        }
        
        public Set<JCIdent> find(JCTree that, Set<JCIdent> v) {
            if (v == null) vars = new HashSet<JCIdent>();
            else vars = v;
            that.accept(this);
            return vars;
        }
        
        public void visitIdent(JCIdent that) {
            vars.add(that);
        }
    } 
    
    /** This class is a tree walker that finds any references to classes in the
     * tree being walked: types of anything, explicit type literals
     * 
     * @author David Cok
     *
     */
    public static class ClassFinder extends JmlTreeScanner {
        
        private Set<Type> types;
        
        public ClassFinder() {}
        
        public static Set<Type> findS(List<? extends JCTree> that, Set<Type> v) {
            ClassFinder vf = new ClassFinder();
            return vf.find(that,v);
        }
        
        public static Set<Type> findS(JCTree that, Set<Type> v) {
            ClassFinder vf = new ClassFinder();
            return vf.find(that,v);
        }
        
        public Set<Type> find(List<? extends JCTree> that, Set<Type> v) {
            if (v == null) types = new HashSet<Type>();
            else types = v;
            for (JCTree t: that) t.accept(this);
            return types;
        }
        
        public Set<Type> find(JCTree that, Set<Type> v) {
            if (v == null) types = new HashSet<Type>();
            else types = v;
            that.accept(this);
            return types;
        }
        
        // visitAnnotation  - FIXME

        public void visitApply(JCMethodInvocation that) {
            super.visitApply(that);
        }

        // visitAssert - statement: just scan the component expressions
        // visitAssign - no new types - just scan the component expressions
        // visitAssignOp - no new types - just scan the component expressions
        // visitBinary - only primitive types
        // visitBlock - statement: just scan the component expressions
        // visitBreak - statement: just scan the component expressions
        // visitCase - statement: just scan the component expressions
        // visitCatch - statement: just scan the component expressions - FIXME - make sure to get the declaration
        // visitClassDef - FIXME ???
        // visitConditional - no new types - just scan the component expressions
        // visitContinue - statement: just scan the component expressions
        // visitDoLoop - statement: just scan the component expressions
        // visitErroneous - statement: just scan the component expressions
        // visitExec - statement: just scan the component expressions
        // visitForEachLoop - statement: just scan the component expressions - FIXME - implied iterator?
        // visitForLoop - statement: just scan the component expressions

        public void visitIdent(JCIdent that) {
            if (!that.type.isPrimitive()) types.add(that.type);
            super.visitIdent(that);
        }
 
        // visitIf - statement: just scan the component expressions
        // visitImport - statement: just scan the component expressions
        // visitIndexed - FIXME
        // visitLabelled - statement: just scan the component expressions
        // visitLetExpr - FIXME
        // visitLiteral - FIXME
        // visitMethodDef - FIXME
        // visitModifiers - no new types
        // visitNewArray - FIXME

        public void visitNewClass(JCNewClass that) {
            types.add(that.type);
        }
        
        // visitParens - no new types - just scan the component expressions
        // visitReturn - statement: just scan the component expressions
        // visitSelect - FIXME
        // visitSkip - statement: just scan the component expressions
        // visitSwitch - statement: just scan the component expressions (FIXME _ might be an Enum)
        // visitSynchronized - statement: just scan the component expressions
        // visitThrow - statement: just scan the component expressions
        // visitTopLevel - statement: just scan the component expressions
        // visitTree
        // visitTry - statement: just scan the component expressions
        // visitTypeApply - FIXME ??
        // visitTypeArray - FIXME ??
        // visitTypeBoundKind - FIXME ??
        // visitTypeCast - FIXME ??

        public void visitTypeIdent(JCPrimitiveTypeTree that) {
            types.add(that.type);
            super.visitTypeIdent(that);
        }
        
        // visitTypeParameter - FIXME ??
        // visitTypeTest (instanceof) - scans the expression and the type
        // visitUnary - only primitive types
        // visitVarDef - FIXME ??
        // visitWhileLoop - statement: just scan the component expressions
        // visitWildcard - FIXME ??
        
        // visitJmlBBArrayAccess - FIXME ?
        // visitJmlBBArrayAssignment - FIXME ?
        // visitJmlBBFieldAccess - FIXME ?
        // visitJmlBBFieldAssignment - FIXME ?
        // visitJmlBinary - no new types - FIXME - subtype?
        // visitJmlClassDecl - FIXME - do specs also
        // visitJmlCompilationUnit - just scan internal components
        // visitJmlConstraintMethodSig - FIXME ?
        // visitJmlDoWhileLoop - FIXME - scan specs
        // visitJmlEnhancedForLoop - FIXME - scan specs
        // visitJmlForLoop - FIXME - scan specs
        // visitJmlGroupName - FIXME??
        // visitJmlImport - no types
        // visitLblExpression - no new types - scan component expressions
        // visitJmlMethodClause... - scan all component expressions - FIXME : watch Decls, Signals, SigOnly
        // visitJmlMethodDecl - FIXME?? - do specs also
        // visitJmlMethodSpecs - FIXME??
        // visitJmlPrimitiveTypeTree - FIXME??
        // visitJmlQuantifiedExpr - FIXME??
        // visitJmlRefines - FIXME??
        // visitJmlSetComprehension - FIXME??
        // visitJmlSingleton - FIXME??
        // visitJmlSpecificationCase - FIXME?? - FIXME??
        // visitJmlStatement - FIXME??
        // visitJmlStatementDecls - FIXME??
        // visitJmlStatementExpr - FIXME??
        // visitJmlStatementLoop - FIXME??
        // visitJmlStatementSpec - FIXME??
        // visitJmlStoreRefArrayRange - FIXME??
        // visitJmlStoreRefKeyword - FIXME??
        // visitJmlStoreRefListExpression - FIXME??
        // visitJmlTypeClause... - scan all components - FIXME - is there more to do?
        // visitJmlVariableDecl - FIXME??
        // visitJmlWhileLoop - FIXME - be sure to scan specs
        
        // FIXME - some things that can probably always be counted on: Object, String, Class
        // FIXME - closure of super types and super interfaces 
    } 
    

    /** This class is a tree walker that finds everything that is the target of
     * a modification in the tree being walked: assignments, assignment-ops, 
     * increment and decrement operators, fields specified as modified by a
     * method call.
     * 
     * FIXME - is the tree already in reduced BasicBlock form?
     * 
     * @author David Cok
     *
     */
    public static class TargetFinder extends JmlTreeScanner {
        
        private List<JCExpression> vars;
        
        public TargetFinder() {}
        
        public static List<JCExpression> findVars(JCTree that, List<JCExpression> v) {
            TargetFinder vf = new TargetFinder();
            return vf.find(that,v);
        }
        
        public static List<JCExpression> findVars(Iterable<? extends JCTree> list, List<JCExpression> v) {
            TargetFinder vf = new TargetFinder();
            return vf.find(list,v);
        }
        
        public List<JCExpression> find(Iterable<? extends JCTree> list, List<JCExpression> v) {
            if (v == null) vars = new ArrayList<JCExpression>();
            else vars = v;
            for (JCTree t: list) t.accept(this);
            return vars;
        }
        
        public List<JCExpression> find(JCTree that, List<JCExpression> v) {
            if (v == null) vars = new ArrayList<JCExpression>();
            else vars = v;
            that.accept(this);
            return vars;
        }
        
        public void visitAssign(JCAssign that) {
            vars.add(that.lhs);
        }
        
        public void visitAssignOp(JCAssign that) {
            vars.add(that.lhs);
        }
        
        public void visitUnary(JCUnary that) {
            int op = that.getTag();
            if (op == JCTree.POSTDEC || op == JCTree.POSTINC ||
                    op == JCTree.PREINC || op == JCTree.PREDEC)
                vars.add(that.getExpression());
        }
        
        // FIXME - also need targets of method calls, update statements of loops,
        // initialization statements of loops

    } 

    /** A Map that caches class info for a given class symbol */
    @NonNull Map<Symbol,JmlClassInfo> classInfoMap = new HashMap<Symbol,JmlClassInfo>();

    /** Returns the jmlClassInfo structure for a class, computing and caching 
     * it if necessary.
     * @param cls the declaration whose info is desired
     * @return the corresponding JmlClassInfo structure; may be null if the
     *   argument has no associated symbol
     */
    //@ modifies (* contents of the classInfoMap *);
    //@ ensures cls.sym != null <==> \result != null;
    @Nullable JmlClassInfo getClassInfo(@NonNull JCClassDecl cls) {
        JmlClassInfo mi = classInfoMap.get(cls.sym);
        if (mi == null) {
            mi = computeClassInfo(cls.sym);
            classInfoMap.put(cls.sym,mi);
        }
        return mi;
    }
    
    @Nullable JmlClassInfo getClassInfo(@NonNull Symbol sym) {
        JmlClassInfo mi = classInfoMap.get(sym);
        if (mi == null) {
            mi = computeClassInfo(sym);
            classInfoMap.put(sym,mi);
        }
        return mi;
    }
    
    // FIXME - when might we have a null tree.sym?
    /** Computes the class information for a given class declaration */
    //@ ensures cls.sym != null <==> \result != null;
    protected @Nullable JmlClassInfo computeClassInfo(Symbol sym) {
        if (sym == null) return null;
        ClassSymbol csym = (ClassSymbol)sym;

        TypeSpecs typeSpecs = specs.get(csym);
        if (typeSpecs == null) {
            System.out.println("UNEXPECTEDLY NO TYPE SPECS " + csym);
            return null;
        }
        JCClassDecl tree = typeSpecs.decl;
        if (tree == null) {
            System.out.println("UNEXPECTEDLY NO DECL " + csym);
        }
        JmlClassInfo classInfo = new JmlClassInfo(tree);
        classInfo.typeSpecs = typeSpecs;
        classInfo.csym = csym;
        
        // FIXME - this comment makes no sense since we do not change anything in the ast

        // Remove any non-Java declarations from the Java AST before we do translation
        // After the superclass translation, we will add back in all the JML stuff.
//        ListBuffer<JCTree> newlist = new ListBuffer<JCTree>();
//        for (JCTree tt: tree.defs) {
//            if (tt == null || tt.getTag() >= JmlTree.JMLFUNCTION) {
//                // skip it
//            } else {
//                newlist.append(tt);
//            }
//        }
        
        Type type = csym.getSuperclass();
        classInfo.superclassInfo = type == null ? null : getClassInfo(type.tsym);

        // Divide up the various type specification clauses into the various types
        ListBuffer<JmlTypeClauseRepresents> represents = new ListBuffer<JmlTypeClauseRepresents>();
        ListBuffer<JCVariableDecl> modelFields = new ListBuffer<JCVariableDecl>();

        if (typeSpecs != null) for (JmlTypeClause c: typeSpecs.clauses) {
            boolean isStatic = c.modifiers != null && (c.modifiers.flags & Flags.STATIC) != 0;
            if (c instanceof JmlTypeClauseDecl) {
                JCTree tt = ((JmlTypeClauseDecl)c).decl;
                if (tt instanceof JCVariableDecl && ((JmlAttr)Attr.instance(context)).isModel(((JCVariableDecl)tt).mods)) {
                    // model field - find represents or make into abstract method
                    modelFields.append((JCVariableDecl)tt);
                } else {
                    // ghost fields, model methods, model classes are used as is
                    //newlist.append(tt);
                }
            } else {
                JmlToken token = c.token;
                if (token == JmlToken.INVARIANT) {
                    if (isStatic) classInfo.staticinvariants.add((JmlTypeClauseExpr)c);
                    else          classInfo.invariants.add((JmlTypeClauseExpr)c);
                } else if (token == JmlToken.REPRESENTS) {
                    JmlTypeClauseRepresents r = (JmlTypeClauseRepresents)c;
                    represents.append(r);
                } else if (token == JmlToken.CONSTRAINT) {
                    if (isStatic) classInfo.staticconstraints.add((JmlTypeClauseConstraint)c);
                    else          classInfo.constraints.add((JmlTypeClauseConstraint)c);
                } else if (token == JmlToken.INITIALLY) {
                    classInfo.initiallys.add((JmlTypeClauseExpr)c);
                } else if (token == JmlToken.AXIOM) {
                    classInfo.axioms.add((JmlTypeClauseExpr)c);
                } else {
                    Log.instance(context).warning("esc.not.implemented","JmlEsc does not yet implement (and ignores) " + token.internedName());
                    // FIXME - readable if, writable if, monitors for, in, maps, initializer, static_initializer, (model/ghost declaration?)
                }
            }
        }
        return classInfo;
    }

    // FIXME _ document
    public static class Tracer extends JmlTreeScanner {
        
        Context context;
        ICounterexample ce;
        Log log;
        
        private static class ReturnException extends RuntimeException {
            private static final long serialVersionUID = -3475328526478936978L;}
        private static class ExException extends RuntimeException {
            private static final long serialVersionUID = -5610207201211221750L;}
        
        public static void trace(Context context, JCMethodDecl decl, ICounterexample s) {
            try {
                decl.accept(new Tracer(context,s));
            } catch (ReturnException e) {
                // ignore
            } catch (ExException e) {
                // ignore
            } catch (RuntimeException e) {
                System.out.println("FAILED : " + e);
            }
            System.out.println("END");
        }
        
        public String getPosition(int p) {
            return log.currentSource().getName() + ":" + log.getLineNumber(p) + " (col " + log.getColumnNumber(p) + "): ";
        }
        
        public Tracer(Context context, ICounterexample s) {
            this.context = context;
            ce = s;
            log = Log.instance(context);
        }
        
        public void visitMethodDef(JCMethodDecl that) {
            System.out.println("START METHOD " + that.sym);
            for (JCVariableDecl param: that.params) {
                String s = param.name + "$" + param.pos + "$0";
                String value = ce.get(s);
                System.out.println("Parameter value: " + param.name + " = " + (value == null ? "<unused>" : value));
            }
            super.visitMethodDef(that);
        }
        
        public void visitIf(JCIf that) {
            String s = "branchCondition$" + that.pos + "$" + 0;
            String value = ce.get(s);
            if (value == null) System.out.println(getPosition(that.pos) + "!!!  Could not find value for branch ("+s+")");
            else {
                System.out.println(getPosition(that.pos) + "Branch condition value: " + value);
                if (value.equals("true")) {
                    if (that.thenpart != null) that.thenpart.accept(this);
                } else if (value.equals("false")) {
                    if (that.elsepart != null) that.elsepart.accept(this);
                } else {
                    System.out.println("!!! Unknown value: " + value);
                }
            }
        }
        
        public void visitAssign(JCAssign that) {
            if (that.lhs instanceof JCIdent) {
                JCIdent id = (JCIdent)that.lhs;
                String s = id.name + "$" + ((VarSymbol)id.sym).pos + "$" + that.pos;
                String value = ce.get(s);
                if (value == null) System.out.println(getPosition(that.pos) + "!!!  Could not find value for variable ("+s+")");
                else System.out.println(getPosition(that.pos) + "Assignment: " + id.name + " = " + value);
            }
        }
        
        public void visitTry(JCTry that) {
            try {
                that.body.accept(this);
            } catch (ReturnException e) {
                // do the finally block
                if (that.finalizer != null) {
                    System.out.println(getPosition(that.finalizer.pos) + "Executing finally block");
                    that.finalizer.accept(this);
                }
                throw e;
            } catch (ExException e) {
                // FIXME
            }
        }
        
        public void visitReturn(JCReturn that) {
            String s = "$$result";
            String value = ce.get(s);
            if (that.expr != null) {
                if (value == null) System.out.println(getPosition(that.pos) + "!!!  Could not find return value ("+s+")");
                else System.out.println(getPosition(that.pos) + "Executed return: value = " + value);
            } else {
                System.out.println(getPosition(that.pos) + "Executed return");
            }
            throw new ReturnException();
        }
    } 
}
