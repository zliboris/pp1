package rs.ac.bg.etf.pp1;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import org.apache.log4j.Logger;

import rs.ac.bg.etf.pp1.ast.*;
import rs.etf.pp1.symboltable.Tab;
import rs.etf.pp1.symboltable.concepts.Obj;
import rs.etf.pp1.symboltable.concepts.Struct;

public class SemanticPass extends VisitorAdaptor {

	boolean errorDetected = false;
	int printCallCount = 0;
	Obj currentMethod = null;
	boolean returnFound = false;
	Struct currentType = null;
	int nVars;
	boolean mainFound = false;
	
	// Bool type - treat as int (0=false, 1=true)
	public static Struct boolType = Tab.intType;
	
	// Enum support
	Struct currentEnumType = null;
	int currentEnumValue = 0;
	Set<Integer> currentEnumValues = null;
	
	// Stack za designator
	Stack<Obj> designatorObjStack = new Stack<>();

	Logger log = Logger.getLogger(getClass());

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
	
	public void visit(Program program) {		
		nVars = Tab.currentScope.getnVars();
		Tab.chainLocalSymbols(program.getProgName().obj);
		// Don't close scope here - let it stay open for code generation
		// Tab.closeScope();
		
		if (!mainFound) {
			report_error("Semanticka greska: program nema main metodu!", null);
		}
	}

	public void visit(ProgName progName) {
		progName.obj = Tab.insert(Obj.Prog, progName.getPName(), Tab.noType);
		Tab.openScope();
		
		// Dodaj bool tip
		Tab.insert(Obj.Type, "bool", boolType);
		// Dodaj eol konstantu
		Tab.insert(Obj.Con, "eol", Tab.charType).setAdr(10);
	}

	public void visit(ScalarVar scalarVar) {
		report_info("Deklarisana promenljiva "+ scalarVar.getVarName(), scalarVar);
		Obj varNode = Tab.insert(Obj.Var, scalarVar.getVarName(), currentType);
	}

	public void visit(ArrayVar arrayVar) {
		report_info("Deklarisan niz "+ arrayVar.getVarName(), arrayVar);
		Obj varNode = Tab.insert(Obj.Var, arrayVar.getVarName(), new Struct(Struct.Array, currentType));
	}

	public void visit(Type type) {
		Obj typeNode = Tab.find(type.getTypeName());
		if (typeNode == Tab.noObj) {
			report_error("Nije pronadjen tip " + type.getTypeName() + " u tabeli simbola", null);
			type.struct = Tab.noType;
		} 
		else {
			if (Obj.Type == typeNode.getKind()) {
				type.struct = typeNode.getType();
			} 
			else {
				report_error("Greska: Ime " + type.getTypeName() + " ne predstavlja tip ", type);
				type.struct = Tab.noType;
			}
		}
		currentType = type.struct;
	}

	public void visit(MethodDecl methodDecl) {
		if (!returnFound && currentMethod.getType() != Tab.noType) {
			report_error("Semanticka greska na liniji " + methodDecl.getLine() + ": funcija " + currentMethod.getName() + " nema return iskaz!", null);
		}
		
		Tab.chainLocalSymbols(currentMethod);
		Tab.closeScope();
		
		returnFound = false;
		currentMethod = null;
	}

	public void visit(TypeMethodTypeName methodTypeName) {
		currentMethod = Tab.insert(Obj.Meth, methodTypeName.getMethName(), methodTypeName.getType().struct);
		methodTypeName.obj = currentMethod;
		Tab.openScope();
		report_info("Obradjuje se funkcija " + methodTypeName.getMethName(), methodTypeName);
	}

	public void visit(VoidMethodTypeName methodTypeName) {
		currentMethod = Tab.insert(Obj.Meth, methodTypeName.getMethName(), Tab.noType);
		methodTypeName.obj = currentMethod;
		Tab.openScope();
		report_info("Obradjuje se funkcija " + methodTypeName.getMethName(), methodTypeName);
		
		if ("main".equals(methodTypeName.getMethName())) {
			mainFound = true;
		}
	}
	
	// ============ PARAMETRI ============
	
	public void visit(ScalarFormalParam param) {
		Tab.insert(Obj.Var, param.getI2(), currentType);
	}

	public void visit(ArrayFormalParam param) {
		Tab.insert(Obj.Var, param.getI2(), new Struct(Struct.Array, currentType));
	}
	
	// ============ KONSTANTE ============
	
	public void visit(ConstAssign constAssign) {
		ConstValue cv = constAssign.getConstValue();
		int value = 0;
		Struct valueType = Tab.noType;

		if (cv instanceof NumConstValue) {
			value = ((NumConstValue) cv).getNumVal();
			valueType = Tab.intType;
		} else if (cv instanceof CharConstValue) {
			value = ((CharConstValue) cv).getCharVal();
			valueType = Tab.charType;
		} else if (cv instanceof BoolConstValue) {
			value = ((BoolConstValue) cv).getBoolVal();
			valueType = boolType;
		}

		if (currentType != null && !currentType.equals(valueType)) {
			report_error("Greska: Tip konstante ne odgovara tipu za " + constAssign.getConstName(), constAssign);
			return;
		}

		report_info("Deklarisana konstanta " + constAssign.getConstName() + " = " + value, constAssign);
		Obj con = Tab.insert(Obj.Con, constAssign.getConstName(), currentType);
		con.setAdr(value);
	}
	
	// ============ NABRAJANJA ============
	
	public void visit(EnumDecl enumDecl) {
		// For MikroJava, enum is essentially int - create a new int-like struct with members
		Struct enumType = new Struct(Struct.Int); // Enum variables are compatible with int
		Tab.chainLocalSymbols(enumType);
		Tab.closeScope();
		
		Obj enumObj = Tab.insert(Obj.Type, enumDecl.getEnumName(), enumType);
		report_info("Deklarisano nabrajanje " + enumDecl.getEnumName(), enumDecl);
		
		currentEnumType = null;
		currentEnumValues = null;
	}
	
	public void visit(EnumConstSimple enumConst) {
		if (currentEnumValues == null) {
			currentEnumValues = new HashSet<>();
			currentEnumValue = 0;
			Tab.openScope();
		}
		
		if (currentEnumValues.contains(currentEnumValue)) {
			report_error("Greska: Duplirana vrednost u nabrajanju", enumConst);
		} else {
			currentEnumValues.add(currentEnumValue);
		}
		
		Obj con = Tab.insert(Obj.Con, enumConst.getEnumConstName(), Tab.intType);
		con.setAdr(currentEnumValue);
		currentEnumValue++;
	}
	
	public void visit(EnumConstWithValue enumConst) {
		if (currentEnumValues == null) {
			currentEnumValues = new HashSet<>();
			currentEnumValue = 0;
			Tab.openScope();
		}
		
		currentEnumValue = enumConst.getEnumVal();
		
		if (currentEnumValues.contains(currentEnumValue)) {
			report_error("Greska: Duplirana vrednost u nabrajanju", enumConst);
		} else {
			currentEnumValues.add(currentEnumValue);
		}
		
		Obj con = Tab.insert(Obj.Con, enumConst.getEnumConstName(), Tab.intType);
		con.setAdr(currentEnumValue);
		currentEnumValue++;
	}

	public void visit(Assignment assignment) {
		Struct exprType = assignment.getExpr().struct;
		Obj desObj = assignment.getDesignator().obj;
		
		if (exprType == null || desObj == null || desObj == Tab.noObj) return;
		
		int kind = desObj.getKind();
		if (kind != Obj.Var && kind != Obj.Elem && kind != Obj.Fld) {
			report_error("Greska: leva strana dodele mora biti promenljiva", assignment);
			return;
		}
		
		// For int-compatible types (enum vars are int-like)
		Struct desType = desObj.getType();
		boolean compatible = exprType.assignableTo(desType);
		// Also allow int to int-like assignment
		if (!compatible && exprType.getKind() == Struct.Int && desType.getKind() == Struct.Int) {
			compatible = true;
		}
		
		if (!compatible)
			report_error("Greska: nekompatibilni tipovi u dodeli vrednosti", assignment);
	}
	
	public void visit(Increment increment) {
		Obj desObj = increment.getDesignator().obj;
		if (desObj == null || desObj == Tab.noObj) return;
		
		int kind = desObj.getKind();
		if (kind != Obj.Var && kind != Obj.Elem && kind != Obj.Fld) {
			report_error("Greska: inkrement samo za promenljive", increment);
		}
		if (desObj.getType() != Tab.intType) {
			report_error("Greska: inkrement samo za int", increment);
		}
	}

	public void visit(IncrementByTwo increment) {
		Obj desObj = increment.getDesignator().obj;
		if (desObj == null || desObj == Tab.noObj) return;

		int kind = desObj.getKind();
		if (kind != Obj.Var && kind != Obj.Elem && kind != Obj.Fld) {
			report_error("Greska: inkrement samo za promenljive", increment);
		}
		if (desObj.getType() != Tab.intType) {
			report_error("Greska: inkrement samo za int", increment);
		}
	}
	
	public void visit(Decrement decrement) {
		Obj desObj = decrement.getDesignator().obj;
		if (desObj == null || desObj == Tab.noObj) return;
		
		int kind = desObj.getKind();
		if (kind != Obj.Var && kind != Obj.Elem && kind != Obj.Fld) {
			report_error("Greska: dekrement samo za promenljive", decrement);
		}
		if (desObj.getType() != Tab.intType) {
			report_error("Greska: dekrement samo za int", decrement);
		}
	}
	
	public void visit(ReadStmt readStmt) {
		Obj desObj = readStmt.getDesignator().obj;
		if (desObj == null || desObj == Tab.noObj) return;
		
		int kind = desObj.getKind();
		if (kind != Obj.Var && kind != Obj.Elem && kind != Obj.Fld) {
			report_error("Greska: read samo za promenljive", readStmt);
		}
		Struct desType = desObj.getType();
		if (desType != Tab.intType && desType != Tab.charType && desType != boolType) {
			report_error("Greska: read prihvata samo int, char ili bool", readStmt);
		}
	}

	public void visit(PrintStmt printStmt){
		printCallCount++;    	
	}

	public void visit(ReturnExpr returnExpr){
		returnFound = true;
		if (currentMethod == null) return;
		Struct currMethType = currentMethod.getType();
		Struct exprType = returnExpr.getExpr().struct;
		if (exprType != null && !currMethType.compatibleWith(exprType)) {
			report_error("Greska: tip izraza u return ne slaze se sa tipom funkcije " + currentMethod.getName(), returnExpr);
		}
	}
	
	public void visit(ReturnNoExpr returnNoExpr) {
		returnFound = true;
		if (currentMethod != null && currentMethod.getType() != Tab.noType) {
			report_error("Greska: return bez vrednosti u ne-void funkciji", returnNoExpr);
		}
	}

	public void visit(ProcCall procCall){
		Obj func = procCall.getDesignator().obj;
		if (Obj.Meth == func.getKind()) { 
			report_info("Pronadjen poziv funkcije " + func.getName() + " na liniji " + procCall.getLine(), null);
		} 
		else {
			report_error("Greska na liniji " + procCall.getLine()+" : ime " + func.getName() + " nije funkcija!", null);
		}     	
	}

	public void visit(ProcCallPars procCall){
		Obj func = procCall.getDesignator().obj;
		if (Obj.Meth == func.getKind()) { 
			report_info("Pronadjen poziv funkcije " + func.getName() + " na liniji " + procCall.getLine(), null);
		} 
		else {
			report_error("Greska na liniji " + procCall.getLine()+" : ime " + func.getName() + " nije funkcija!", null);
		}     	
	}    

	public void visit(AddExpr addExpr) {
		Struct te = addExpr.getExprNonTernList().struct;
		Struct t = addExpr.getTerm().struct;
		// Both must be int or int-compatible (enum values are int)
		if ((te.getKind() == Struct.Int) && (t.getKind() == Struct.Int))
			addExpr.struct = Tab.intType;
		else {
			report_error("Greska na liniji "+ addExpr.getLine()+" : nekompatibilni tipovi u izrazu za sabiranje.", null);
			addExpr.struct = Tab.noType;
		} 
	}

	public void visit(TermExpr termExpr) {
		termExpr.struct = termExpr.getTerm().struct;
	}

	public void visit(MinusExpr minusExpr) {
		minusExpr.struct = minusExpr.getTerm().struct;
	}

	public void visit(ExprNonTern exprNonTern) {
		exprNonTern.struct = exprNonTern.getExprNonTernList().struct;
	}

	public void visit(NonTernExpr nonTernExpr) {
		nonTernExpr.struct = nonTernExpr.getExprNonTern().struct;
	}

	public void visit(Term term) {
		term.struct = term.getTermList().struct;    	
	}

	public void visit(SingleFactor singleFactor){
		singleFactor.struct = singleFactor.getFactor().struct;    	
	}

	public void visit(MulExpr mulExpr){
		mulExpr.struct = mulExpr.getFactor().struct;    	
	}

	public void visit(NumConst cnst){
		cnst.struct = Tab.intType;    	
	}

	public void visit(CharConst cnst){
		cnst.struct = Tab.charType;    	
	}

	public void visit(BoolConst cnst){
		cnst.struct = boolType;    	
	}
	
	public void visit(Var var) {
		Obj obj = var.getDesignator().obj;
		if (obj != null && obj != Tab.noObj) {
			var.struct = obj.getType();
		} else {
			var.struct = Tab.noType;
		}
	}
	
	public void visit(NewArray newArray) {
		Struct exprType = newArray.getExpr().struct;
		if (exprType != null && exprType != Tab.intType) {
			report_error("Greska: velicina niza mora biti int", newArray);
		}
		newArray.struct = new Struct(Struct.Array, newArray.getType().struct);
	}
	
	public void visit(ParenExpr parenExpr) {
		parenExpr.struct = parenExpr.getExpr().struct;
	}
	
	// ============ TERNARNI IZRAZ ============
	
	// Pomocna promenljiva za prenos tipa iz TernaryExpr u TernExpr
	private Struct lastTernaryType = Tab.noType;
	
	public void visit(TernExpr ternExpr) {
		ternExpr.struct = lastTernaryType;
	}
	
	public void visit(TernaryExpr ternaryExpr) {
		Struct e1 = ternaryExpr.getExpr().struct;
		Struct e2 = ternaryExpr.getExpr1().struct;
		if (e1 != null && e2 != null && e1.equals(e2)) {
			lastTernaryType = e1;
		} else {
			report_error("Greska: tipovi u ternarnom izrazu moraju biti isti", ternaryExpr);
			lastTernaryType = Tab.noType;
		}
	}

	public void visit(FuncCall funcCall){
		Obj func = funcCall.getDesignator().obj;
		if (Obj.Meth == func.getKind()) { 
			report_info("Pronadjen poziv funkcije " + func.getName() + " na liniji " + funcCall.getLine(), null);
			funcCall.struct = func.getType();
		} 
		else {
			report_error("Greska na liniji " + funcCall.getLine()+" : ime " + func.getName() + " nije funkcija!", null);
			funcCall.struct = Tab.noType;
		}
	}

	public void visit(FuncCallNoPars funcCall){
		Obj func = funcCall.getDesignator().obj;
		if (Obj.Meth == func.getKind()) { 
			report_info("Pronadjen poziv funkcije " + func.getName() + " na liniji " + funcCall.getLine(), null);
			funcCall.struct = func.getType();
		} 
		else {
			report_error("Greska na liniji " + funcCall.getLine()+" : ime " + func.getName() + " nije funkcija!", null);
			funcCall.struct = Tab.noType;
		}
	}

	public void visit(DesignatorIdent ident){
		Obj obj = Tab.find(ident.getName());
		if (obj == Tab.noObj) { 
			report_error("Greska: ime "+ident.getName()+" nije deklarisano!", ident);
		}
		designatorObjStack.push(obj);
	}
	
	public void visit(DesignatorField field) {
		Obj parentObj = designatorObjStack.pop();
		Struct parentType = parentObj.getType();
		
		// For enum access (Broj.NULA), parentObj is an Obj.Type with int-like struct containing members
		if (parentObj.getKind() == Obj.Type && parentType.getMembers() != null) {
			boolean found = false;
			for (Obj member : parentType.getMembers()) {
				if (member.getName().equals(field.getName())) {
					designatorObjStack.push(member);
					found = true;
					break;
				}
			}
			if (!found) {
				report_error("Greska: clan " + field.getName() + " ne postoji u tipu " + parentObj.getName(), field);
				designatorObjStack.push(Tab.noObj);
			}
		} else {
			report_error("Greska: pristup polju na pogresnom tipu", field);
			designatorObjStack.push(Tab.noObj);
		}
	}
	
	public void visit(DesignatorLength length) {
		Obj parentObj = designatorObjStack.pop();
		Struct parentType = parentObj.getType();
		
		if (parentType.getKind() != Struct.Array) {
			report_error("Greska: length samo nad nizovima", length);
		}
		Obj lengthObj = new Obj(Obj.Fld, "length", Tab.intType);
		designatorObjStack.push(lengthObj);
	}
	
	public void visit(DesignatorArray array) {
		Obj parentObj = designatorObjStack.pop();
		Struct parentType = parentObj.getType();
		
		if (parentType.getKind() != Struct.Array) {
			report_error("Greska: indeksiranje samo nad nizovima", array);
			designatorObjStack.push(new Obj(Obj.Elem, "elem", Tab.noType));
		} else {
			Struct indexType = array.getExpr().struct;
			// Index must be int or int-compatible (enum values)
			if (indexType != null && indexType.getKind() != Struct.Int) {
				report_error("Greska: indeks mora biti int", array);
			}
			Obj elemObj = new Obj(Obj.Elem, parentObj.getName(), parentType.getElemType());
			designatorObjStack.push(elemObj);
		}
	}
	
	public void visit(Designator designator) {
		if (!designatorObjStack.isEmpty()) {
			designator.obj = designatorObjStack.pop();
		} else {
			designator.obj = Tab.noObj;
		}
	}
	
	public boolean passed() {
		return !errorDetected;
	}
}
