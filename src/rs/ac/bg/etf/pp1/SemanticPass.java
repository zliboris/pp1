package rs.ac.bg.etf.pp1;

import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import rs.ac.bg.etf.pp1.ast.*;
import rs.etf.pp1.symboltable.*;
import rs.etf.pp1.symboltable.concepts.*;
import rs.etf.pp1.symboltable.factory.SymbolTableFactory;
import rs.etf.pp1.symboltable.structure.HashTableDataStructure;
import rs.etf.pp1.symboltable.structure.SymbolDataStructure;

public class SemanticPass extends VisitorAdaptor {

	int varDeclCount = 0;
	boolean errorDetected = false;
	int nVars;

    enum RelOp {EQ, NEQ, LT, LTE, GT, GTE};
    RelOp currentRelOp = null;

    Struct currentType = null;
    Struct currentExtendsClassType = null;
    Struct currentClassType = null;

	Struct currentMethodReturnType = null;
	boolean returnFound = false;
    int currentMethodFormalParamCount = 0;

    Deque<Obj> methodCallObjStack = new ArrayDeque<>();
    Deque<Integer> actualParamCountStack = new ArrayDeque<>();

    int currentConstIntValue = 0;
    char currentConstCharValue = 0;
    int currentConstBoolValue = 0;
    enum ConstType {NONE, INT, CHAR, BOOL};
    ConstType currentConstType = ConstType.NONE;

    SymbolDataStructure currentEnumConsts = null;
    int currentEnumConstValue = 0;
    Struct currentEnumType = null;

    boolean inClass = false;
    boolean inMethodScope = false;
    Map<SwitchStmt, Set<Integer>> switchCaseValues = new HashMap<>();
    Map<Struct, Map<String, Obj>> classMembersByType = new HashMap<>();
	
	Logger log = Logger.getLogger(getClass());

    private boolean isNumericType(Struct type){
        return type != null && (type.getKind() == Struct.Int || type.getKind() == Struct.Enum);
    }

    private boolean isReadablePrintableType(Struct type){
        if(type == null) return false;
        int kind = type.getKind();
        return kind == Struct.Int || kind == Struct.Char || kind == Struct.Bool || kind == Struct.Enum;
    }

    private boolean isLValueDesignator(Obj obj){
        if(obj == null) return false;
        int kind = obj.getKind();
        return kind == Obj.Var || kind == Obj.Fld || kind == Obj.Elem;
    }

    private boolean isTypeAssignable(Struct srcType, Struct dstType){
        if(srcType == null || dstType == null) return false;
        if(srcType.assignableTo(dstType)) return true;
        if(srcType.compatibleWith(dstType) && dstType.compatibleWith(srcType)) return true;

        if(srcType.getKind() == Struct.Array && dstType.getKind() == Struct.Array){
            return isTypeAssignable(srcType.getElemType(), dstType.getElemType());
        }

        if(srcType.getKind() == Struct.Class || srcType.getKind() == Struct.Interface){
            Struct curr = srcType;
            while(curr != null){
                if(curr.assignableTo(dstType)) return true;
                if(curr.compatibleWith(dstType) && dstType.compatibleWith(curr)) return true;
                curr = curr.getElemType();
            }
        }

        return false;
    }

    private boolean hasAncestorOfType(SyntaxNode node, Class<?> ancestorType){
        SyntaxNode current = node;
        while(current != null){
            if(ancestorType.isInstance(current)) return true;
            current = current.getParent();
        }
        return false;
    }

    private SwitchStmt getEnclosingSwitch(SyntaxNode node){
        SyntaxNode current = node;
        while(current != null){
            if(current instanceof SwitchStmt) return (SwitchStmt) current;
            current = current.getParent();
        }
        return null;
    }

    private void pushMethodCallContext(Obj methodObj){
        methodCallObjStack.push(methodObj);
        actualParamCountStack.push(0);
    }

    private Obj peekMethodCallObj(){
        return methodCallObjStack.isEmpty() ? null : methodCallObjStack.peek();
    }

    private int peekActualParamCount(){
        return actualParamCountStack.isEmpty() ? 0 : actualParamCountStack.peek();
    }

    private void incActualParamCount(){
        if(actualParamCountStack.isEmpty()) return;
        int curr = actualParamCountStack.pop();
        actualParamCountStack.push(curr + 1);
    }

    private void popMethodCallContext(){
        if(!methodCallObjStack.isEmpty()) methodCallObjStack.pop();
        if(!actualParamCountStack.isEmpty()) actualParamCountStack.pop();
    }

    private List<Obj> getFormalParams(Obj methodObj){
        List<Obj> formalParams = new ArrayList<>();
        if(methodObj == null) return formalParams;

        for(Obj sym : methodObj.getLocalSymbols()){
            if(sym.getKind() == Obj.Var && sym.getFpPos() >= 0){
                formalParams.add(sym);
            }
        }

        formalParams.sort(Comparator.comparingInt(Obj::getFpPos));
        return formalParams;
    }

    private void copyMethodSignature(Obj sourceMethod, Obj targetMethod){
        Tab.openScope();
        for(Obj sym : sourceMethod.getLocalSymbols()){
            Obj copied = Tab.insert(sym.getKind(), sym.getName(), sym.getType());
            copied.setFpPos(sym.getFpPos());
            copied.setAdr(sym.getAdr());
            copied.setLevel(sym.getLevel());
        }
        Tab.chainLocalSymbols(targetMethod);
        Tab.closeScope();
        targetMethod.setLevel(sourceMethod.getLevel());
        targetMethod.setAdr(sourceMethod.getAdr());
    }

    private void registerCurrentClassMember(Obj memberObj){
        if(currentClassType == null || memberObj == null) return;
        classMembersByType.computeIfAbsent(currentClassType, k -> new HashMap<>()).put(memberObj.getName(), memberObj);
    }

    private Obj findMemberInClassHierarchy(Struct classType, String memberName){
        Struct curr = classType;
        while(curr != null){
            Map<String, Obj> memberMap = classMembersByType.get(curr);
            if(memberMap != null){
                Obj candidate = memberMap.get(memberName);
                if(candidate != null) return candidate;
            }

            for(Obj m : curr.getMembers()){
                if(m.getName().equals(memberName)) return m;
            }

            curr = curr.getElemType();
        }

        return null;
    }

	public void report_error(String message, SyntaxNode info) {
		errorDetected = true;
		StringBuilder msg = new StringBuilder(message);
		int line = (info == null) ? 0: info.getLine();
		if (line != 0)
			msg.append (" na liniji ").append(line);
		log.error(msg.toString());
	}

	public void report_info(String message, SyntaxNode info) {
		StringBuilder msg = new StringBuilder(message); 
		int line = (info == null) ? 0: info.getLine();
		if (line != 0)
			msg.append (" na liniji ").append(line);
		log.info(msg.toString());
	}

    public void visit(ProgName progName){
		Tab.insert(Obj.Type, "bool", new Struct(Struct.Bool));
        progName.obj = Tab.insert(Obj.Prog, progName.getPName(), Tab.noType);
        Tab.openScope();
    }

    public void visit(Program program){
        nVars = Tab.currentScope.getnVars();
        Tab.chainLocalSymbols(program.getProgName().obj);

        boolean validMainFound = false;
        for(Obj obj : program.getProgName().obj.getLocalSymbols()){
            if(obj.getKind() == Obj.Meth && "main".equals(obj.getName()) && obj.getType() == Tab.noType && obj.getLevel() == 0){
                validMainFound = true;
                break;
            }
        }
        if(!validMainFound){
            report_error("Greska: Ne postoji ispravna main metoda (void main() bez argumenata)!", program);
        }

        Tab.closeScope();
    }
	
    public void visit(VarDeclCorrect varDeclCorrect){
        currentType = null;
    }
    
    public void visit(Type type){
        Obj typeNode = Tab.find(type.getTypeName());
        if(typeNode == Tab.noObj){
            report_error("Nije pronadjen tip " + type.getTypeName() + " u tabeli simbola! ", null);
            type.struct = Tab.noType;
        }else{
            if(Obj.Type == typeNode.getKind()){
                type.struct = typeNode.getType();
                currentType = type.struct;
            }else{
                report_error("Greska: Ime " + type.getTypeName() + " ne predstavlja tip!", type);
                type.struct = Tab.noType;
            }
        }
    }


    public void visit(ScalarVar scalarVar){
        if(Tab.currentScope.findSymbol(scalarVar.getVarName()) != null){
            report_error("Greska: Varijabla " + scalarVar.getVarName() + " je vec deklarisana u trenutnom opsegu!", scalarVar);
        } else {
            if(currentType == Tab.noType){
                report_error("Greska: Varijabla " + scalarVar.getVarName() + " ne moze biti deklarisana sa nedozvoljenim tipom!", scalarVar);
            } else {
                if(inClass && !inMethodScope){
                    Obj fldObj = Tab.insert(Obj.Fld, scalarVar.getVarName(), currentType);
                    registerCurrentClassMember(fldObj);
                }
                else{
                    Tab.insert(Obj.Var, scalarVar.getVarName(), currentType);
                }
            }
        }
    }

    public void visit(ArrayVar arrayVar){
        if(Tab.currentScope.findSymbol(arrayVar.getVarName()) != null){
            report_error("Greska: Varijabla " + arrayVar.getVarName() + " je vec deklarisana u trenutnom opsegu!", arrayVar);
        } else {
            if(currentType == Tab.noType){
                report_error("Greska: Varijabla " + arrayVar.getVarName() + " ne moze biti deklarisana sa nedozvoljenim tipom!", arrayVar);
            } else {
                if(inClass && !inMethodScope){
                    Obj fldArrObj = Tab.insert(Obj.Fld, arrayVar.getVarName(), new Struct(Struct.Array, currentType));
                    registerCurrentClassMember(fldArrObj);
                }
                else{
                    Tab.insert(Obj.Var, arrayVar.getVarName(), new Struct(Struct.Array, currentType));
                }
            }
        }
    }

    public void visit(NumConstValue numConstValue){
        currentConstIntValue = numConstValue.getNumVal();
        currentConstType = ConstType.INT;
    }

    public void visit(CharConstValue charConstValue){
        currentConstCharValue = charConstValue.getCharVal();
        currentConstType = ConstType.CHAR;
    }

    public void visit(BoolConstValue boolConstValue){
        currentConstBoolValue = boolConstValue.getBoolVal();
        currentConstType = ConstType.BOOL;
    }

    public void visit(ConstAssign constAssign){
        if(Tab.currentScope.findSymbol(constAssign.getConstName()) != null){
            report_error("Greska: Konstanta " + constAssign.getConstName() + " je vec deklarisana u trenutnom opsegu!", constAssign);
        } else {
            if(currentType == Tab.noType){
                report_error("Greska: Konstanta " + constAssign.getConstName() + " ne moze biti deklarisana sa nedozvoljenim tipom!", constAssign);
            } else {
                if(currentConstType == ConstType.NONE){
                    report_error("Greska: Konstanta " + constAssign.getConstName() + " nema dodeljenu vrednost!", constAssign);
                    return;
                }
                Obj constObj = Tab.insert(Obj.Con, constAssign.getConstName(), currentType);
                int typeKind = currentType.getKind();
                if(typeKind == Struct.Int){
                    if(currentConstType != ConstType.INT){
                        report_error("Greska: Konstanta " + constAssign.getConstName() + " je deklarisana kao int, ali joj nije dodeljena int vrednost!", constAssign);
                    } else {
                        report_info("Deklarisana konstanta " + constAssign.getConstName() + " tipa int sa vrednoscu " + currentConstIntValue, constAssign);
                        constObj.setAdr(currentConstIntValue);
                    }
                } else if(typeKind == Struct.Char){
                    if(currentConstType != ConstType.CHAR){
                        report_error("Greska: Konstanta " + constAssign.getConstName() + " je deklarisana kao char, ali joj nije dodeljena char vrednost!", constAssign);
                    } else {
                        report_info("Deklarisana konstanta " + constAssign.getConstName() + " tipa char sa vrednoscu '" + currentConstCharValue + "'", constAssign);
                        constObj.setAdr(currentConstCharValue);
                    }
                } else if(typeKind == Struct.Bool){
                    if(currentConstType != ConstType.BOOL){
                        report_error("Greska: Konstanta " + constAssign.getConstName() + " je deklarisana kao bool, ali joj nije dodeljena bool vrednost!", constAssign);
                    } else {
                        report_info("Deklarisana konstanta " + constAssign.getConstName() + " tipa bool sa vrednoscu " + (currentConstBoolValue == 1 ? "true" : "false"), constAssign);
                        constObj.setAdr(currentConstBoolValue);
                    }
                }
            }
        }
        currentConstBoolValue = 0;
    	currentConstCharValue = 0;
        currentConstIntValue = 0;
        currentConstType = ConstType.NONE;
    }

    public void visit(ConstDecl constDecl){
        currentType = null;
    }

    public void visit(EnumTypeDecl enumTypeDecl){
        enumTypeDecl.struct = new Struct(Struct.Enum);
        Tab.insert(Obj.Type, enumTypeDecl.getEnumName(), enumTypeDecl.struct);
        Tab.openScope();
        currentEnumConsts = SymbolTableFactory.instance().createSymbolTableDataStructure();
        currentEnumConstValue = 0;
        currentEnumType = enumTypeDecl.struct;
    }

    public void visit(EnumConstSimple enumConstSimple){
        if(Tab.currentScope.findSymbol(enumConstSimple.getEnumConstName()) != null){
            report_error("Greska: Enum konstanta " + enumConstSimple.getEnumConstName() + " je vec deklarisana u trenutnom opsegu!", enumConstSimple);
        } else {
            if(currentEnumConsts.searchKey(enumConstSimple.getEnumConstName()) != null){
                report_error("Greska: Enum konstanta " + enumConstSimple.getEnumConstName() + " je vec deklarisana u okviru trenutnog enum tipa!", enumConstSimple);
                return;
            }

            Obj enumConstObj = Tab.insert(Obj.Con, enumConstSimple.getEnumConstName(), currentEnumType);
            enumConstObj.setAdr(currentEnumConstValue);
            currentEnumConsts.insertKey(enumConstObj);
            report_info("Deklarisana enum konstanta " + enumConstSimple.getEnumConstName() + " sa vrednoscu " + (currentEnumConstValue), enumConstSimple);
            currentEnumConstValue++;
        }
    }

    public void visit(EnumConstWithValue enumConstWithValue){
        if(Tab.currentScope.findSymbol(enumConstWithValue.getEnumConstName()) != null){
            report_error("Greska: Enum konstanta " + enumConstWithValue.getEnumConstName() + " je vec deklarisana u trenutnom opsegu!", enumConstWithValue);
        } else {
            if(currentEnumConsts.searchKey(enumConstWithValue.getEnumConstName()) != null){
                report_error("Greska: Enum konstanta " + enumConstWithValue.getEnumConstName() + " je vec deklarisana u okviru trenutnog enum tipa!", enumConstWithValue);
                return;
            }
            if(enumConstWithValue.getEnumVal() < currentEnumConstValue){
                report_error("Greska: Enum konstanta " + enumConstWithValue.getEnumConstName() + " ima vrednost koja je vec dodeljena drugoj konstanti!", enumConstWithValue);
                return;
            }
            Obj enumConstObj = Tab.insert(Obj.Con, enumConstWithValue.getEnumConstName(), currentEnumType);
            enumConstObj.setAdr(enumConstWithValue.getEnumVal());
            currentEnumConsts.insertKey(enumConstObj);
            report_info("Deklarisana enum konstanta " + enumConstWithValue.getEnumConstName() + " sa vrednoscu " + (enumConstWithValue.getEnumVal()), enumConstWithValue);
            currentEnumConstValue = enumConstWithValue.getEnumVal() + 1;
        }
    }

    public void visit(EnumDecl enumDecl){
        Tab.chainLocalSymbols(enumDecl.getEnumTypeDecl().struct);
        Tab.closeScope();
        currentEnumConsts = null;
        currentEnumConstValue = 0;
        currentEnumType = null;
    }

    public void visit(ClassName className){
        className.struct = new Struct(Struct.Class);
        Tab.insert(Obj.Type, className.getClassName(), className.struct);
        currentClassType = className.struct;
        classMembersByType.putIfAbsent(currentClassType, new HashMap<>());
        inClass = true;
        Tab.openScope();
    }

    public void visit(ClassDecl classDecl){
        Tab.chainLocalSymbols(classDecl.getClassName().struct);
        Tab.closeScope();
        currentClassType = null;
        inClass = false;
    }

    public void visit(AbstractClassName abstractClassName){
        abstractClassName.struct = new Struct(Struct.Interface);
        Tab.insert(Obj.Type, abstractClassName.getClassName(), abstractClassName.struct);
        currentClassType = abstractClassName.struct;
        classMembersByType.putIfAbsent(currentClassType, new HashMap<>());
        inClass = true;
        Tab.openScope();
    }

    public void visit(AbstractClassDecl abstractClassDecl){
        Tab.chainLocalSymbols(abstractClassDecl.getAbstractClassName().struct);
        Tab.closeScope();
        currentClassType = null;
        inClass = false;
    }

    public void visit(AbstractMethodDeclType abstractMethodDeclType){
        if(Tab.currentScope.findSymbol(abstractMethodDeclType.getMethName()) != null){
            report_error("Greska: Apstraktna metoda " + abstractMethodDeclType.getMethName() + " je vec deklarisana u trenutnom opsegu!", abstractMethodDeclType);
            return;
        }

        Obj absMethodObj = Tab.insert(Obj.Meth, abstractMethodDeclType.getMethName(), abstractMethodDeclType.getType().struct);
        absMethodObj.setLevel(0);
        registerCurrentClassMember(absMethodObj);
    }

    public void visit(AbstractMethodDeclVoid abstractMethodDeclVoid){
        if(Tab.currentScope.findSymbol(abstractMethodDeclVoid.getMethName()) != null){
            report_error("Greska: Apstraktna metoda " + abstractMethodDeclVoid.getMethName() + " je vec deklarisana u trenutnom opsegu!", abstractMethodDeclVoid);
            return;
        }

        Obj absMethodObj = Tab.insert(Obj.Meth, abstractMethodDeclVoid.getMethName(), Tab.noType);
        absMethodObj.setLevel(0);
        registerCurrentClassMember(absMethodObj);
    }
    
    public void visit(ExtendsType extendsType){
        Obj parentClass = Tab.find(extendsType.getType().getTypeName());
        if(parentClass == Tab.noObj){
            report_error("Greska: Nije pronadjena klasa " + extendsType.getType().getTypeName() + " u tabeli simbola!", extendsType);
            return;
        }
        if(parentClass.getKind() != Obj.Type || (parentClass.getType().getKind() != Struct.Class && parentClass.getType().getKind() != Struct.Interface)){
            report_error("Greska: Ime " + extendsType.getType().getTypeName() + " ne predstavlja klasu!", extendsType);
            return;
        }
        if(currentClassType != null){
            currentClassType.setElementType(parentClass.getType());
        }
        for(Obj obj : extendsType.getType().struct.getMembers()){
            if(obj.getKind() != Obj.Meth){
                Obj insertedMember = Tab.insert(obj.getKind(), obj.getName(), obj.getType());
                registerCurrentClassMember(insertedMember);
            }
        }
        currentType = null;
        currentExtendsClassType = parentClass.getType();
    }

    public void visit(ClassVarDeclList classVarDeclList){
        if(currentExtendsClassType != null){
            for(Obj obj : currentExtendsClassType.getMembers()){
                if(obj.getKind() == Obj.Meth){
                    Obj insertedMethod = Tab.insert(obj.getKind(), obj.getName(), obj.getType());
                    copyMethodSignature(obj, insertedMethod);
                    registerCurrentClassMember(insertedMethod);
                }
            }
        }
        currentExtendsClassType = null;
    }

    public void visit(TypeMethodTypeName methodTypeName){
        methodTypeName.obj = Tab.insert(Obj.Meth, methodTypeName.getMethName(), methodTypeName.getType().struct);
        registerCurrentClassMember(methodTypeName.obj);
        inMethodScope = true;
        Tab.openScope();
        if(inClass && currentClassType != null){
            Tab.insert(Obj.Var, "this", currentClassType).setFpPos(-1);
        }
        report_info("Obradjuje se funkcija " + methodTypeName.getMethName(), methodTypeName);
        currentMethodReturnType = methodTypeName.getType().struct;
    }

    public void visit(VoidMethodTypeName methodTypeName){
        methodTypeName.obj = Tab.insert(Obj.Meth, methodTypeName.getMethName(), Tab.noType);
        registerCurrentClassMember(methodTypeName.obj);
        inMethodScope = true;
        Tab.openScope();
        if(inClass && currentClassType != null){
            Tab.insert(Obj.Var, "this", currentClassType).setFpPos(-1);
        }
        report_info("Obradjuje se funkcija " + methodTypeName.getMethName(), methodTypeName);
        currentMethodReturnType = Tab.noType;
    }

    public void visit(MethodDecl methodDecl){
        if(!returnFound && methodDecl.getMethodTypeName().obj.getType() != Tab.noType){
            report_error("Semanticka greska na liniji " + methodDecl.getLine() + ": funkcija " + methodDecl.getMethodTypeName().obj.getName() + " nema return iskaz!", null);
        }
        Tab.chainLocalSymbols(methodDecl.getMethodTypeName().obj);
        methodDecl.getMethodTypeName().obj.setLevel(currentMethodFormalParamCount);
        Tab.closeScope();
        inMethodScope = false;
        
        returnFound = false;
        currentMethodReturnType = null;
        currentMethodFormalParamCount = 0;
    }

    public void visit(ScalarFormalParam scalarFormalParam){
        if(Tab.currentScope.findSymbol(scalarFormalParam.getParamName()) != null){
            report_error("Greska: Formalni parametar " + scalarFormalParam.getParamName() + " je vec deklarisan u trenutnom opsegu!", scalarFormalParam);
        } else {
            if(currentType == Tab.noType){
                report_error("Greska: Formalni parametar " + scalarFormalParam.getParamName() + " ne moze biti deklarisan sa nedozvoljenim tipom!", scalarFormalParam);
            } else {
                Tab.insert(Obj.Var, scalarFormalParam.getParamName(), currentType).setFpPos(currentMethodFormalParamCount);
            }
        }
        currentType = null;
        currentMethodFormalParamCount++;
    }

    public void visit(ArrayFormalParam arrayFormalParam){
        if(Tab.currentScope.findSymbol(arrayFormalParam.getParamName()) != null){
            report_error("Greska: Formalni parametar " + arrayFormalParam.getParamName() + " je vec deklarisan u trenutnom opsegu!", arrayFormalParam);
        } else {
            if(currentType == Tab.noType){
                report_error("Greska: Formalni parametar " + arrayFormalParam.getParamName() + " ne moze biti deklarisan sa nedozvoljenim tipom!", arrayFormalParam);
            } else {
                Tab.insert(Obj.Var, arrayFormalParam.getParamName(), new Struct(Struct.Array, currentType)).setFpPos(currentMethodFormalParamCount);
            }
        }
        currentType = null;
        currentMethodFormalParamCount++;
    }

    public void visit(ReturnExpr returnExpr){
        if(currentMethodReturnType == null){
            report_error("Greska: Return iskaz nije unutar funkcije!", returnExpr);
        } else {
            if(currentMethodReturnType == Tab.noType){
                report_error("Greska: Return iskaz sa vrednoscu nije dozvoljen u funkciji koja nema povratnu vrednost!", returnExpr);
            } else {
                if(!isTypeAssignable(returnExpr.getExpr().struct, currentMethodReturnType)){
                    report_error("Greska: Tip vrednosti u return iskazu ne odgovara povratnom tipu funkcije!", returnExpr);
                }
            }
        }
        returnFound = true;
    }

    public void visit(ReturnStmt returnStmt){
        if(currentMethodReturnType == null){
            report_error("Greska: Return iskaz nije unutar funkcije!", returnStmt);
        } else {
            if(currentMethodReturnType != Tab.noType){
                report_error("Greska: Return iskaz bez vrednosti nije dozvoljen u funkciji koja ima povratnu vrednost!", returnStmt);
            }
        }
        returnFound = true;
    }

    public void visit(NumConst numConst){
        numConst.struct = Tab.intType;
    }

    public void visit(CharConst charConst){
        charConst.struct = Tab.charType;
    }

    public void visit(BoolConst boolConst){
        boolConst.struct = Tab.find("bool").getType();
    }

    public void visit(NewObj newObj){
        Struct typeNode = newObj.getType().struct;
        if(typeNode.getKind() == Struct.Class){
            newObj.struct = typeNode;
        } else {
            report_error("Greska: Ime " + newObj.getType().getTypeName() + " ne predstavlja klasu!", newObj);
            newObj.struct = Tab.noType;
        }
    }

    public void visit(NewArray newArray){
        Struct typeNode = newArray.getType().struct;
        if(typeNode == Tab.noType){
            report_error("Greska: Tip " + newArray.getType().getTypeName() + " nije dozvoljen kao element niza!", newArray);
            newArray.struct = Tab.noType;
        } else {
            newArray.struct = new Struct(Struct.Array, typeNode);
        }
    }

    public void visit(ParenExpr parenExpr){
        parenExpr.struct = parenExpr.getExpr().struct;
    }

    public void visit(Var var){
        var.struct = var.getDesignator().obj.getType();
    }

    public void visit(FuncCall funcCall){
        Obj calledObj = funcCall.getDesignatorFuncCall().obj;
        if(calledObj == null || calledObj == Tab.noObj){
            report_error("Greska: Nije moguce izvrsiti poziv nepoznatog simbola!", funcCall);
            funcCall.struct = Tab.noType;
            return;
        }

        if(calledObj.getKind() != Obj.Meth){
            report_error("Greska: Ime " + calledObj.getName() + " ne predstavlja funkciju!", funcCall);
            funcCall.struct = Tab.noType;
        } else {

            int actualCount = peekActualParamCount();
            if(actualCount != calledObj.getLevel()){
                report_error("Greska: Funkcija " + calledObj.getName() + " zahteva " + calledObj.getLevel() + " parametara, a pozvana je sa " + actualCount + " parametara!", funcCall);
            }

            funcCall.struct = calledObj.getType();
            popMethodCallContext();
        }
    }

    public void visit(FuncCallNoPars funcCallNoPars){
        Obj funcObj = funcCallNoPars.getDesignatorFuncCall().obj;
        if(funcObj == null || funcObj == Tab.noObj){
            report_error("Greska: Nije moguce izvrsiti poziv nepoznatog simbola!", funcCallNoPars);
            funcCallNoPars.struct = Tab.noType;
            return;
        }

        if(funcObj.getKind() != Obj.Meth){
            report_error("Greska: Ime " + funcObj.getName() + " ne predstavlja funkciju!", funcCallNoPars);
            funcCallNoPars.struct = Tab.noType;
        } else {
            if(funcObj.getLevel() != 0){
                report_error("Greska: Funkcija " + funcObj.getName() + " zahteva formalne parametre, a pozvana je bez actual parametara!", funcCallNoPars);
            }
            funcCallNoPars.struct = funcObj.getType();
            popMethodCallContext();
        }
    }

    public void visit(DesignatorFuncCall designatorFuncCall){
        designatorFuncCall.obj = designatorFuncCall.getDesignator().obj;
        if(designatorFuncCall.obj != null && designatorFuncCall.obj.getKind() == Obj.Meth){
            pushMethodCallContext(designatorFuncCall.obj);
        }
    }

    public void visit(ActParsSingle actParsSingle){
        Obj currentMethodCallObj = peekMethodCallObj();
        if(currentMethodCallObj == null){
            report_error("Greska: Stvarni parametri su navedeni uz nedozvoljen poziv funkcije!", actParsSingle);
            return;
        }
        int currentActualParamCount = peekActualParamCount();
        List<Obj> formalParams = getFormalParams(currentMethodCallObj);
        if(formalParams.size() <= currentActualParamCount){
            report_error("Greska: Funkcija " + currentMethodCallObj.getName() + " ima premalo argumenata!", actParsSingle);
        } else {
            Obj formalParam = formalParams.get(currentActualParamCount);
            if(!isTypeAssignable(actParsSingle.getExpr().struct, formalParam.getType())){
                report_error("Greska: Tip actual parametra nije kompatibilan sa tipom formalnog parametra funkcije " + currentMethodCallObj.getName() + "!", actParsSingle);
            }
        }
        incActualParamCount();
    }

    public void visit(ActParsList actParsList){
        Obj currentMethodCallObj = peekMethodCallObj();
        if(currentMethodCallObj == null){
            report_error("Greska: Stvarni parametri su navedeni uz nedozvoljen poziv funkcije!", actParsList);
            return;
        }
        int currentActualParamCount = peekActualParamCount();
        List<Obj> formalParams = getFormalParams(currentMethodCallObj);
        if(formalParams.size() <= currentActualParamCount){
            report_error("Greska: Funkcija " + currentMethodCallObj.getName() + " ima premalo argumenata!", actParsList);
        } else {
            Obj formalParam = formalParams.get(currentActualParamCount);
            if(!isTypeAssignable(actParsList.getExpr().struct, formalParam.getType())){
                report_error("Greska: Tip actual parametra nije kompatibilan sa tipom formalnog parametra funkcije " + currentMethodCallObj.getName() + "!", actParsList);
            }
        }
        incActualParamCount();
    }

    public void visit(Designator designator){
        designator.obj = designator.getDesignatorList().obj;

        SyntaxNode parent = designator.getParent();
        if((parent instanceof DesignatorProcCallIsClass || parent instanceof ProcCall || parent instanceof ProcCallPars)
                && designator.obj != null && designator.obj.getKind() == Obj.Meth){
            pushMethodCallContext(designator.obj);
        }
    }

    public void visit(DesignatorLeftSide designatorLeftSide){
        designatorLeftSide.obj = designatorLeftSide.getDesignator().obj;
    }

    public void visit(Assignment assignment){
        Obj dstObj = assignment.getDesignatorLeftSide().obj;
        if(!isLValueDesignator(dstObj)){
            report_error("Greska: Leva strana dodele mora biti promenljiva, element niza ili polje objekta!", assignment);
            return;
        }

        if(!isTypeAssignable(assignment.getExpr().struct, dstObj.getType())){
            report_error("Greska: Tip izraza na desnoj strani nije dodeljiv levoj strani!", assignment);
        }
    }

    public void visit(Increment increment){
        Obj designatorObj = increment.getDesignator().obj;
        if(!isLValueDesignator(designatorObj)){
            report_error("Greska: Operator ++ moze da se primeni samo nad promenljivom, poljem ili elementom niza!", increment);
            return;
        }
        if(!isNumericType(designatorObj.getType())){
            report_error("Greska: Operator ++ zahteva operand tipa int/enum!", increment);
        }
    }

    public void visit(Decrement decrement){
        Obj designatorObj = decrement.getDesignator().obj;
        if(!isLValueDesignator(designatorObj)){
            report_error("Greska: Operator -- moze da se primeni samo nad promenljivom, poljem ili elementom niza!", decrement);
            return;
        }
        if(!isNumericType(designatorObj.getType())){
            report_error("Greska: Operator -- zahteva operand tipa int/enum!", decrement);
        }
    }

    public void visit(DesignatorProcCallIsClass designatorProcCallIsClass){
        designatorProcCallIsClass.obj = designatorProcCallIsClass.getDesignator().obj;
    }

    public void visit(ProcCall procCall){
        Obj procObj = procCall.getDesignatorProcCallIsClass().obj;
        if(procObj.getKind() != Obj.Meth){
            report_error("Greska: Ime " + procObj.getName() + " ne predstavlja funkciju/proceduru!", procCall);
        } else if(procObj.getLevel() != 0){
            report_error("Greska: Funkcija " + procObj.getName() + " zahteva formalne parametre, a pozvana je bez actual parametara!", procCall);
        }
        popMethodCallContext();
    }

    public void visit(ProcCallPars procCallPars){
        Obj procObj = procCallPars.getDesignatorProcCallIsClass().obj;
        int actualCount = peekActualParamCount();
        if(procObj.getKind() != Obj.Meth){
            report_error("Greska: Ime " + procObj.getName() + " ne predstavlja funkciju/proceduru!", procCallPars);
        } else if(actualCount != procObj.getLevel()){
            report_error("Greska: Funkcija " + procObj.getName() + " zahteva " + procObj.getLevel() + " parametara, a pozvana je sa " + actualCount + " parametara!", procCallPars);
        }
        popMethodCallContext();
    }

    public void visit(ReadStmt readStmt){
        Obj readObj = readStmt.getDesignatorLeftSide().obj;
        if(!isLValueDesignator(readObj)){
            report_error("Greska: read zahteva promenljivu, element niza ili polje objekta kao argument!", readStmt);
            return;
        }
        if(!isReadablePrintableType(readObj.getType())){
            report_error("Greska: read podrzava samo int/char/bool/enum tipove!", readStmt);
        }
    }

    public void visit(PrintStmt printStmt){
        if(!isReadablePrintableType(printStmt.getExpr().struct)){
            report_error("Greska: print podrzava samo int/char/bool/enum tipove!", printStmt);
        }
    }

    public void visit(PrintStmtWithWidth printStmtWithWidth){
        if(!isReadablePrintableType(printStmtWithWidth.getExpr().struct)){
            report_error("Greska: print podrzava samo int/char/bool/enum tipove!", printStmtWithWidth);
        }
    }

    public void visit(SimpleCondFact simpleCondFact){
        Struct boolType = Tab.find("bool").getType();
        if(!simpleCondFact.getExprNonTern().struct.compatibleWith(boolType)){
            report_error("Greska: Uslov bez relacijskog operatora mora biti tipa bool!", simpleCondFact);
        }
    }

    public void visit(SwitchStmt switchStmt){
        if(!isNumericType(switchStmt.getExpr().struct)){
            report_error("Greska: switch izraz mora biti tipa int/enum!", switchStmt);
        }
    }

    public void visit(CaseStmt caseStmt){
        SwitchStmt enclosingSwitch = getEnclosingSwitch(caseStmt);
        if(enclosingSwitch == null) return;

        Set<Integer> switchCases = switchCaseValues.computeIfAbsent(enclosingSwitch, k -> new HashSet<>());
        if(!switchCases.add(caseStmt.getCaseNumber().getCaseVal())){
            report_error("Greska: Duplicirana case vrednost " + caseStmt.getCaseNumber().getCaseVal() + " u istom switch iskazu!", caseStmt);
        }
    }

    public void visit(BreakStmt breakStmt){
        if(!hasAncestorOfType(breakStmt, ForStmt.class) && !hasAncestorOfType(breakStmt, SwitchStmt.class)){
            report_error("Greska: break je dozvoljen samo unutar for/switch iskaza!", breakStmt);
        }
    }

    public void visit(ContinueStmt continueStmt){
        if(!hasAncestorOfType(continueStmt, ForStmt.class)){
            report_error("Greska: continue je dozvoljen samo unutar for petlje!", continueStmt);
        }
    }

    public void visit(DesignatorIdent designatorIdent){
        Obj obj = Tab.find(designatorIdent.getName());
        if(obj == Tab.noObj){
            report_error("Greska: Ime " + designatorIdent.getName() + " nije pronadjeno u tabeli simbola!", designatorIdent);
            designatorIdent.obj = Tab.noObj;
        } else {
            designatorIdent.obj = obj;
        }
    }

    public void visit(DesignatorField designatorField){
        Obj baseObj = designatorField.getDesignatorList().obj;

        if(baseObj != null && "this".equals(baseObj.getName()) && currentClassType != null){
            Obj thisMember = findMemberInClassHierarchy(currentClassType, designatorField.getName());
            if(thisMember == null){
                report_error("Greska: Klasa " + currentClassType + " nema polje " + designatorField.getName() + "!", designatorField);
                designatorField.obj = Tab.noObj;
            } else {
                designatorField.obj = thisMember;
            }
            return;
        }

        Struct baseType = designatorField.getDesignatorList().obj.getType();

        if(baseType.getKind() != Struct.Class &&
                baseType.getKind() != Struct.Enum &&
                baseType.getKind() != Struct.Interface){
            report_error("Greska: Tip " + designatorField.getDesignatorList().obj.getType() + " nije klasa niti enum, pa se ne mogu pristupiti njena polja!", designatorField);
            designatorField.obj = Tab.noObj;
        } else {
            Obj fieldObj = findMemberInClassHierarchy(baseType, designatorField.getName());

            if(fieldObj == null){
                report_error("Greska: Klasa " + designatorField.getDesignatorList().obj.getType() + " nema polje " + designatorField.getName() + "!", designatorField);
                designatorField.obj = Tab.noObj;
            } else {
                designatorField.obj = fieldObj;
            }
        }
    }

    public void visit(DesignatorLength designatorLength){
        if(designatorLength.getDesignatorList().obj.getType().getKind() != Struct.Array){
            report_error("Greska: Tip " + designatorLength.getDesignatorList().obj.getType() + " nije niz, pa se ne moze pristupiti njegovoj duzini!", designatorLength);
            designatorLength.obj = Tab.noObj;
        } else {
            designatorLength.obj = new Obj(Obj.Fld, "length", Tab.intType);
        }
    }

    public void visit(DesignatorArray designatorArray){
        if(designatorArray.getDesignatorList().obj.getType().getKind() != Struct.Array){
            report_error("Greska: Tip " + designatorArray.getDesignatorList().obj.getType() + " nije niz, pa se ne mogu pristupiti njegovi elementi!", designatorArray);
            designatorArray.obj = Tab.noObj;
        } else {

            if(designatorArray.getExpr().struct.getKind() != Struct.Int && designatorArray.getExpr().struct.getKind() != Struct.Enum){
                report_error("Greska: Indeks niza nije tipa int!", designatorArray);
                designatorArray.obj = Tab.noObj;
            } else {
                Struct elemType = designatorArray.getDesignatorList().obj.getType().getElemType();
                designatorArray.obj = new Obj(Obj.Elem, "temp", elemType);
            }
        }
    }

    public void visit(Term term){
        term.struct = term.getTermList().struct;
    }

    public void visit(SingleFactor singleFactor){
        singleFactor.struct = singleFactor.getFactor().struct;
    }

    public void visit(MulExpr mulExpr){
        if(mulExpr.getTermList().struct.getKind() != Struct.Int && mulExpr.getTermList().struct.getKind() != Struct.Enum ||
           mulExpr.getFactor().struct.getKind() != Struct.Int && mulExpr.getFactor().struct.getKind() != Struct.Enum){
            report_error("Greska: Operandi u izrazu nisu oba tipa int!", mulExpr);
            mulExpr.struct = Tab.noType;
        } else {
            mulExpr.struct = Tab.intType;
        }
    }

    public void visit(ExprWithOptMinus exprWithOptMinus){
        if(exprWithOptMinus.getOptMinus() instanceof HasMinus){
            if(exprWithOptMinus.getTerm().struct.getKind() != Struct.Int && exprWithOptMinus.getTerm().struct.getKind() != Struct.Enum){
                report_error("Greska: Operand u izrazu nije tipa int!", exprWithOptMinus);
                exprWithOptMinus.struct = Tab.noType;
            } else {
                exprWithOptMinus.struct = Tab.intType;
            }
        } else {
            exprWithOptMinus.struct = exprWithOptMinus.getTerm().struct;
        }
    }

    public void visit(AddExpr addExpr){
        SyntaxNode errorNode = addExpr.getTerm() != null ? addExpr.getTerm() : addExpr;
        if(addExpr.getExprNonTern().struct.getKind() != Struct.Int && addExpr.getExprNonTern().struct.getKind() != Struct.Enum ||
           addExpr.getTerm().struct.getKind() != Struct.Int && addExpr.getTerm().struct.getKind() != Struct.Enum){
            report_error("Greska: Operandi u izrazu nisu oba tipa int!", errorNode);
            addExpr.struct = Tab.noType;
        } else {
                addExpr.struct = Tab.intType;
        }
    }

    public void visit(TernaryExpr ternaryExpr){
        if(ternaryExpr.getExpr().struct.compatibleWith(ternaryExpr.getExpr1().struct)){
            ternaryExpr.struct = ternaryExpr.getExpr().struct;
        } else {
            report_error("Greska: Izrazi u ternarnom operatoru nisu kompatibilnih tipova!", ternaryExpr);
            ternaryExpr.struct = Tab.noType;
        }
    }

    public void visit(NonTernExpr nonTernExpr){
        nonTernExpr.struct = nonTernExpr.getExprNonTern().struct;
    }

    public void visit(TernExpr ternExpr){
        ternExpr.struct = ternExpr.getExprTern().struct;
    }

    public void visit(RelopCondFact relopCondFact){
        if(relopCondFact.getExprNonTern().struct.compatibleWith(relopCondFact.getExprNonTern1().struct)){
            if(relopCondFact.getExprNonTern().struct.getKind() == Struct.Class || relopCondFact.getExprNonTern().struct.getKind() == Struct.Array){
                if(currentRelOp != RelOp.EQ && currentRelOp != RelOp.NEQ){
                    report_error("Greska: Relacijski operator " + currentRelOp + " nije dozvoljen za tipove klasa i nizova!", relopCondFact);
                }
            }
        } else {
            report_error("Greska: Izrazi u relacijskom operatoru nisu kompatibilnih tipova!", relopCondFact);
        }
    }

    public void visit(Equalsop equalsop){
        currentRelOp = RelOp.EQ;
    }

    public void visit(Notequalsop notequalsop){
        currentRelOp = RelOp.NEQ;
    }

    public void visit(Lessop lessop){
        currentRelOp = RelOp.LT;
    }

    public void visit(Lessequalop lessequalop){
        currentRelOp = RelOp.LTE;
    }

    public void visit(Greaterop greaterop){
        currentRelOp = RelOp.GT;
    }

    public void visit(Greaterequalop greaterequalop){
        currentRelOp = RelOp.GTE;
    }

    


	public boolean passed(){
    	return !errorDetected;
    }
    
}
