package rs.ac.bg.etf.pp1;

import rs.ac.bg.etf.pp1.CounterVisitor.FormParamCounter;
import rs.ac.bg.etf.pp1.CounterVisitor.VarCounter;
import rs.ac.bg.etf.pp1.ast.*;
import rs.etf.pp1.mj.runtime.Code;
import rs.etf.pp1.symboltable.Tab;
import rs.etf.pp1.symboltable.concepts.Obj;
import rs.etf.pp1.symboltable.concepts.Struct;

public class CodeGenerator extends VisitorAdaptor {
	
	private int mainPc;
	
	public int getMainPc() {
		return mainPc;
	}
	
	@Override
	public void visit(TypeMethodTypeName methodTypeName) {
		if ("main".equalsIgnoreCase(methodTypeName.getMethName())) {
			mainPc = Code.pc;
		}
		methodTypeName.obj.setAdr(Code.pc);
		
		// Collect arguments and local variables.
		SyntaxNode methodNode = methodTypeName.getParent();
		VarCounter varCnt = new VarCounter();
		methodNode.traverseTopDown(varCnt);
		FormParamCounter fpCnt = new FormParamCounter();
		methodNode.traverseTopDown(fpCnt);
		
		// Generate the entry.
		Code.put(Code.enter);
		Code.put(fpCnt.getCount());
		Code.put(varCnt.getCount() + fpCnt.getCount());
	}

	@Override
	public void visit(VoidMethodTypeName methodTypeName) {
		if ("main".equalsIgnoreCase(methodTypeName.getMethName())) {
			mainPc = Code.pc;
		}
		methodTypeName.obj.setAdr(Code.pc);
		
		// Collect arguments and local variables.
		SyntaxNode methodNode = methodTypeName.getParent();
		VarCounter varCnt = new VarCounter();
		methodNode.traverseTopDown(varCnt);
		FormParamCounter fpCnt = new FormParamCounter();
		methodNode.traverseTopDown(fpCnt);
		
		// Generate the entry.
		Code.put(Code.enter);
		Code.put(fpCnt.getCount());
		Code.put(varCnt.getCount() + fpCnt.getCount());
	}
	
	@Override
	public void visit(MethodDecl MethodDecl) {
		Code.put(Code.exit);
		Code.put(Code.return_);
	}
	
	@Override
	public void visit(ReturnExpr ReturnExpr) {
		Code.put(Code.exit);
		Code.put(Code.return_);
	}
	
	@Override
	public void visit(ReturnNoExpr ReturnNoExpr) {
		Code.put(Code.exit);
		Code.put(Code.return_);
	}
	
	@Override
	public void visit(Assignment assignment) {
		Code.store(assignment.getDesignator().obj);
	}
	
	@Override
	public void visit(NumConst numConst) {
		Code.loadConst(numConst.getNumVal());
	}
	
	@Override
	public void visit(CharConst charConst) {
		Code.loadConst(charConst.getCharVal());
	}
	
	@Override
	public void visit(BoolConst boolConst) {
		Code.loadConst(boolConst.getBoolVal());
	}
	
	@Override
	public void visit(Var var) {
		Obj obj = var.getDesignator().obj;
		// Skip loading for array.length - arraylength instruction already put value on stack
		if (obj.getKind() == Obj.Fld && "length".equals(obj.getName())) {
			return;
		}
		Code.load(obj);
	}
	
	@Override
	public void visit(FuncCall funcCall) {
		Obj functionObj = funcCall.getDesignator().obj;
		String funcName = functionObj.getName();
		
		// Handle built-in functions
		if ("ord".equals(funcName)) {
			// ord is a no-op in MikroJava - char value is already an int on stack
			return;
		} else if ("chr".equals(funcName)) {
			// chr is a no-op in MikroJava - int value can be used as char
			return;
		} else if ("len".equals(funcName)) {
			// len uses arraylength instruction, but argument is array not on stack
			// Actually len(arr) - arr is already on stack as argument, so:
			Code.put(Code.arraylength);
			return;
		}
		
		int offset = functionObj.getAdr() - Code.pc; 
		Code.put(Code.call);
		Code.put2(offset);
	}

	@Override
	public void visit(FuncCallNoPars funcCall) {
		Obj functionObj = funcCall.getDesignator().obj;
		int offset = functionObj.getAdr() - Code.pc; 
		Code.put(Code.call);
		Code.put2(offset);
	}
	
	@Override
	public void visit(PrintStmt printStmt) {
		Struct type = printStmt.getExpr().struct;
		Code.loadConst(5); // width
		if (type != null && type.getKind() == Struct.Char) {
			Code.put(Code.bprint);
		} else {
			Code.put(Code.print);
		}
	}
	
	@Override
	public void visit(PrintStmtNum printStmtNum) {
		Struct type = printStmtNum.getExpr().struct;
		Code.loadConst(printStmtNum.getN2()); // width
		if (type != null && type.getKind() == Struct.Char) {
			Code.put(Code.bprint);
		} else {
			Code.put(Code.print);
		}
	}
	
	@Override
	public void visit(ReadStmt readStmt) {
		Obj desObj = readStmt.getDesignator().obj;
		Struct type = desObj.getType();
		if (type != null && type.getKind() == Struct.Char) {
			Code.put(Code.bread);
		} else {
			Code.put(Code.read);
		}
		Code.store(desObj);
	}
	
	// ============ ARITMETICKI IZRAZI ============
	
	@Override
	public void visit(AddExpr addExpr) {
		Addop op = addExpr.getAddop();
		if (op instanceof PlusOp) {
			Code.put(Code.add);
		} else {
			Code.put(Code.sub);
		}
	}
	
	@Override
	public void visit(MinusExpr minusExpr) {
		Code.put(Code.neg);
	}
	
	@Override
	public void visit(MulExpr mulExpr) {
		Mulop op = mulExpr.getMulop();
		if (op instanceof TimesOp) {
			Code.put(Code.mul);
		} else if (op instanceof DivOp) {
			Code.put(Code.div);
		} else {
			Code.put(Code.rem);
		}
	}
	
	// ============ INCREMENT/DECREMENT ============
	
	@Override
	public void visit(Increment increment) {
		Obj obj = increment.getDesignator().obj;
		if (obj.getKind() == Obj.Elem) {
			Code.put(Code.dup2);
		}
		Code.load(obj);
		Code.loadConst(1);
		Code.put(Code.add);
		Code.store(obj);
	}
	
	@Override
	public void visit(Decrement decrement) {
		Obj obj = decrement.getDesignator().obj;
		if (obj.getKind() == Obj.Elem) {
			Code.put(Code.dup2);
		}
		Code.load(obj);
		Code.loadConst(1);
		Code.put(Code.sub);
		Code.store(obj);
	}
	
	// ============ NEW ============
	
	@Override
	public void visit(NewArray newArray) {
		// Expr je vec na steku (velicina niza)
		Code.put(Code.newarray);
		if (newArray.getType().struct.getKind() == Struct.Char) {
			Code.put(0); // byte array
		} else {
			Code.put(1); // word array
		}
	}
	
	// ============ POZIVI PROCEDURA ============
	
	@Override
	public void visit(ProcCall procCall) {
		Obj func = procCall.getDesignator().obj;
		int offset = func.getAdr() - Code.pc;
		Code.put(Code.call);
		Code.put2(offset);
		// void funkcija - nema return na steku, ali ako ima, moramo ga skinuti
		if (func.getType() != Tab.noType) {
			Code.put(Code.pop);
		}
	}
	
	@Override
	public void visit(ProcCallPars procCall) {
		Obj func = procCall.getDesignator().obj;
		int offset = func.getAdr() - Code.pc;
		Code.put(Code.call);
		Code.put2(offset);
		if (func.getType() != Tab.noType) {
			Code.put(Code.pop);
		}
	}
	
	// ============ DESIGNATOR ARRAY (za pristup elementu niza) ============
	
	@Override
	public void visit(DesignatorArray array) {
		// Adresa niza je vec na steku od DesignatorIdent
		// Indeks je vec izracunat od Expr
		// Dakle na steku imamo: [adr, index]
		// Ne radimo nista specijalno jer Code.load/store ce raditi sa Elem tipom
	}
	
	@Override 
	public void visit(DesignatorLength length) {
		// Adresa niza je na steku
		Code.put(Code.arraylength);
	}
	
	@Override
	public void visit(DesignatorIdent ident) {
		SyntaxNode parent = ident.getParent();
		Obj obj = Tab.find(ident.getName());
		
		// Ako je tip (enum ime), ne ucitavamo nista - cekamo DesignatorField
		if (obj.getKind() == Obj.Type) {
			return;
		}
		
		// Ako je parent DesignatorArray ili DesignatorLength, ucitaj adresu niza
		if (parent instanceof DesignatorArray || parent instanceof DesignatorLength) {
			Code.load(obj); // Ucitaj adresu niza na stek
		}
	}
	
	@Override
	public void visit(DesignatorField field) {
		// Za enum pristup (Broj.NULA) - field.obj je konstanta sa vrednoscu
		// Samo ucitamo vrednost konstante
		// Napomena: obj je postavljen u SemanticPass
	}
	
	// ============ RELACIONI IZRAZI ============
	
	private int getRelopCode(Relop relop) {
		if (relop instanceof Equalsop) return Code.eq;
		if (relop instanceof Notequalsop) return Code.ne;
		if (relop instanceof Lessop) return Code.lt;
		if (relop instanceof Lessequalop) return Code.le;
		if (relop instanceof Greaterop) return Code.gt;
		if (relop instanceof Greaterequalop) return Code.ge;
		return Code.eq; // default
	}
	
	@Override
	public void visit(RelopCondFact relopCondFact) {
		// Oba izraza su vec na steku (left, right)
		// jcc uporedi dva vrha steka i skoci ako je uslov tacan
		int relopCode = getRelopCode(relopCondFact.getRelop());
		
		// jcc skace ako je uslov tacan
		Code.put(Code.jcc + relopCode);
		int jumpIfTrue = Code.pc;
		Code.put2(0); // placeholder
		
		// Uslov nije tacan - gurni 0
		Code.loadConst(0);
		Code.put(Code.jmp);
		int skipToEnd = Code.pc;
		Code.put2(0);
		
		// Uslov je tacan - gurni 1
		Code.fixup(jumpIfTrue);
		Code.loadConst(1);
		
		// Kraj
		Code.fixup(skipToEnd);
	}
	
	// ============ TERNARNI IZRAZI ============
	
	// Stack za ternarne adrese za patchovanje
	private java.util.Stack<Integer> ternJumpToElse = new java.util.Stack<>();
	private java.util.Stack<Integer> ternJumpToEnd = new java.util.Stack<>();
	
	@Override
	public void visit(TernCondDone ternCondDone) {
		// Uslov je na steku - ako je 0 (false), skoci na else granu
		// Za CondFact sa relopom, treba specijalno obraditi - za sada samo "if false jump"
		Code.loadConst(0);
		Code.put(Code.jcc + Code.eq); // jump if equal to 0 (false)
		ternJumpToElse.push(Code.pc); // sacuvaj adresu za patchovanje
		Code.put2(0); // placeholder za adresu
	}
	
	@Override
	public void visit(TernThenDone ternThenDone) {
		// Then grana je na steku, skoci na kraj
		Code.put(Code.jmp);
		ternJumpToEnd.push(Code.pc);
		Code.put2(0); // placeholder
		
		// Patchiraj skok na else - on treba da skoci ovde
		int jumpToElseAddr = ternJumpToElse.pop();
		Code.fixup(jumpToElseAddr);
	}
	
	@Override
	public void visit(TernaryExpr ternaryExpr) {
		// Else grana je na steku
		// Patchiraj skok na kraj - on treba da skoci ovde
		int jumpToEndAddr = ternJumpToEnd.pop();
		Code.fixup(jumpToEndAddr);
	}
}
