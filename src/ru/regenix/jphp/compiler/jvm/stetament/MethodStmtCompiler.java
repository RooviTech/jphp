package ru.regenix.jphp.compiler.jvm.stetament;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import ru.regenix.jphp.compiler.common.misc.StackItem;
import ru.regenix.jphp.compiler.jvm.misc.JumpItem;
import ru.regenix.jphp.compiler.jvm.misc.LocalVariable;
import ru.regenix.jphp.compiler.jvm.node.MethodNodeImpl;
import ru.regenix.jphp.runtime.env.Environment;
import ru.regenix.jphp.runtime.memory.ArrayMemory;
import ru.regenix.jphp.runtime.memory.Memory;
import ru.regenix.jphp.runtime.reflection.MethodEntity;
import ru.regenix.jphp.runtime.reflection.ParameterEntity;
import ru.regenix.jphp.tokenizer.TokenMeta;
import ru.regenix.jphp.tokenizer.token.Token;
import ru.regenix.jphp.tokenizer.token.stmt.ArgumentStmtToken;
import ru.regenix.jphp.tokenizer.token.stmt.ExprStmtToken;
import ru.regenix.jphp.tokenizer.token.stmt.MethodStmtToken;
import ru.regenix.jphp.tokenizer.token.stmt.ReturnStmtToken;

import java.util.*;

public class MethodStmtCompiler extends StmtCompiler<MethodEntity> {

    public final ClassStmtCompiler clazz;
    public final MethodStmtToken method;
    public final MethodNode node;

    private Stack<StackItem> stack = new Stack<StackItem>();
    private final List<JumpItem> jumpStack = new ArrayList<JumpItem>();

    private int stackSize = 0;
    private int stackMaxSize = 0;

    private Map<String, LocalVariable> localVariables;
    protected String realName;

    private boolean external = false;

    public MethodStmtCompiler(ClassStmtCompiler clazz, MethodNode node){
        super(clazz.getCompiler());
        this.clazz = clazz;
        this.method = null;
        this.node   = node;

        this.localVariables = new LinkedHashMap<String, LocalVariable>();

        entity = new MethodEntity(getCompiler().getContext());
        entity.setClazz(clazz.entity);
        entity.setName(node.name);
        realName = entity.getName();
    }

    public MethodStmtCompiler(ClassStmtCompiler clazz, MethodStmtToken method) {
        super(clazz.getCompiler());
        this.clazz = clazz;
        this.method = method;
        this.node  = new MethodNodeImpl();

        this.localVariables = new LinkedHashMap<String, LocalVariable>();

        entity = new MethodEntity(getCompiler().getContext());
        entity.setClazz(clazz.entity);
        entity.setName(method.getName().getName());
        realName = entity.getName();
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }

    public Map<String, LocalVariable> getLocalVariables() {
        return localVariables;
    }

    void pushJump(LabelNode breakLabel, LabelNode continueLabel, int stackSize){
        jumpStack.add(new JumpItem(breakLabel, continueLabel, stackSize));
    }

    void pushJump(LabelNode breakLabel, LabelNode continueLabel){
        pushJump(breakLabel, continueLabel, 0);
    }

    JumpItem getJump(int level){
        if (jumpStack.size() - level < 0)
            return null;
        if (jumpStack.size() - level >= jumpStack.size())
            return null;

        return jumpStack.get(jumpStack.size() - level);
    }

    int getJumpStackSize(int level){
        int size = 0;
        for(int i = jumpStack.size(); i >= 0 && jumpStack.size() - i < level; i--){
            JumpItem item = getJump(i);
            size += item.stackSize;
        }
        return size;
    }

    void popJump(){
        jumpStack.remove(jumpStack.size() - 1);
    }

    void push(StackItem item){
        stack.push(item);
        stackSize += item.size;
        if (stackMaxSize < stackSize)
            stackMaxSize = stackSize;
    }

    int getStackSize(){
        return stackSize;
    }

    int getStackCount(){
        return stack.size();
    }

    StackItem pop(){
        StackItem item = stack.pop();
        stackSize -= item.size;
        return item;
    }

    void popAll(){
        stackSize = 0;
        stack.clear();
    }

    StackItem peek(){
        return stack.peek();
    }

    LocalVariable addLocalVariable(String variable, LabelNode label, Class clazz){
        LocalVariable result;
        localVariables.put(
                variable,
                result = new LocalVariable(variable, localVariables.size(), label, clazz)
        );
        return result;
    }

    LocalVariable addLocalVariable(String variable, LabelNode label){
        return addLocalVariable(variable, label, Memory.class);
    }

    LocalVariable getLocalVariable(String variable){
        return localVariables.get(variable);
    }

    void writeHeader(){
        int access = 0;
        if (method != null){
            switch (method.getModifier()){
                case PRIVATE: access += Opcodes.ACC_PRIVATE; break;
                case PROTECTED: access += Opcodes.ACC_PROTECTED; break;
                case PUBLIC: access += Opcodes.ACC_PUBLIC; break;
            }

            if (method.isStatic()) access += Opcodes.ACC_STATIC;
            if (method.isAbstract()) access += Opcodes.ACC_ABSTRACT;
            if (method.isFinal()) access += Opcodes.ACC_FINAL;

            node.access = access;
            node.name = method.getName().getName();
            node.desc = Type.getMethodDescriptor(
                    Type.getType(Memory.class),
                    Type.getType(Environment.class),
                    Type.getType(String.class),
                    Type.getType(Memory[].class)
            );

            if (external){
                node.desc = Type.getMethodDescriptor(
                                Type.getType(Memory.class),
                                Type.getType(Environment.class),
                                Type.getType(String.class),
                                Type.getType(Memory[].class),
                                Type.getType(ArrayMemory.class)
                        );
            }
        }

        if (method != null){
            LabelNode label = writeLabel(node, method.getMeta().getStartLine());

            if (!method.isStatic())
                addLocalVariable("this", label, Object.class);

            addLocalVariable("~env", label, Environment.class); // Environment env
            addLocalVariable("~static", label, String.class);
            LocalVariable args = addLocalVariable("~args", label, Memory[].class);  // Memory[] arguments

            if (method.isDynamicLocal()){
                if (external)
                    addLocalVariable("~passedLocal", label, ArrayMemory.class);

                LocalVariable local = addLocalVariable("~local", label, ArrayMemory.class);
                ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(this, null);

                if (external){
                    expressionCompiler.writeVarLoad("~passedLocal");
                    expressionCompiler.writeSysStaticCall(ArrayMemory.class, "valueOfRef", ArrayMemory.class, ArrayMemory.class);
                    expressionCompiler.setStackPeekAsImmutable();
                    expressionCompiler.writeVarStore(local, false, true);
                } else {
                    expressionCompiler.writePushNewObject(ArrayMemory.class);
                    expressionCompiler.writeVarStore(local, false, true);
                }
            }

            int i = 0;
            for(ArgumentStmtToken argument : method.getArguments()){
                LocalVariable local = addLocalVariable(argument.getName().getName(), label, Memory.class);

                ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(this, null);
                expressionCompiler.writeVarLoad(args);
                expressionCompiler.writePushGetFromArray(i, Memory.class);
                expressionCompiler.writeVarStore(local, false, false);

                i++;
            }
        } else {
            LabelNode label = writeLabel(node, clazz.clazz.getMeta().getStartLine());
        }
    }

    @SuppressWarnings("unchecked")
    void writeFooter(){
        LabelNode endL = new LabelNode();
        node.instructions.add(endL);

        for(LocalVariable variable : localVariables.values()){
            String description = Type.getDescriptor(variable.getClazz() == null ? Object.class : variable.getClazz());
            if (variable.name.equals("this"))
                description = "L" + clazz.clazz.getFulledName('/') + ";";

            node.localVariables.add(new LocalVariableNode(
                    variable.name,
                    description,
                    null,
                    variable.label,
                    endL,
                    variable.index
            ));
        }
        //node.maxStack = this.stackMaxSize;  !!! we don't need this, see: ClassWriter.COMPUTE_FRAMES
        //node.maxLocals = this.localVariables.size();
    }

    @Override
    public MethodEntity compile() {
        if (method != null){
            if (external)
                method.setDynamicLocal(true);

            entity.setAbstract(method.isAbstract());
            entity.setFinal(method.isFinal());
            entity.setStatic(method.isStatic());
            entity.setModifier(method.getModifier());
            entity.setReturnReference(method.isReturnReference());

            ParameterEntity[] parameters = new ParameterEntity[method.getArguments().size()];
            int i = 0;
            for(ArgumentStmtToken argument : method.getArguments()){
                parameters[i] = new ParameterEntity(compiler.getContext());
                parameters[i].setMethod(entity);
                parameters[i].setReference(argument.isReference());
                parameters[i].setName(argument.getName().getName());

                if (argument.getValue() != null){
                    ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(this, null);
                    Memory result = expressionCompiler.writeExpression(argument.getValue(), true, true);
                    if (result == null){
                        unexpectedToken(argument.getValue().getTokens().get(0));
                    }
                    parameters[i].setDefaultValue( result );
                }
                i++;
            }
            entity.setParameters(parameters);
        }

        writeHeader();

        if (method != null && method.getBody() != null){
            for(ExprStmtToken instruction : method.getBody().getInstructions()){
                compiler.compileExpression(this, instruction);
            }
        }

        ReturnStmtToken token = new ReturnStmtToken(new TokenMeta("", 0, 0, 0, 0));
        token.setValue(new ExprStmtToken(Token.of("null")));
        compiler.compileExpression(this, new ExprStmtToken(token));

        writeFooter();
        return entity;
    }

}
