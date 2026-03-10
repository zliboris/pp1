package rs.ac.bg.etf.pp1;

import rs.ac.bg.etf.pp1.ast.*;

import rs.etf.pp1.mj.runtime.Code;
import rs.etf.pp1.symboltable.Tab;
import rs.etf.pp1.symboltable.concepts.Obj;
import rs.etf.pp1.symboltable.concepts.Struct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public class CodeGenerator extends VisitorAdaptor {

	public int numOfStaticVars;

	private Stack<Integer> skipCondFact = new Stack<>();
	private Stack<Integer> skipCondition = new Stack<>();
	private Stack<Integer> skipThen = new Stack<>();
	private Stack<Integer> skipElse = new Stack<>();

	private Stack<Integer> forInc = new Stack<>();

	private Stack<List<Integer>> Break = new Stack<>();

	private Stack<List<Integer>> SwitchCaseAddr = new Stack<>();
	private Stack<List<Integer>> SwitchCaseNumber = new Stack<>();
	private Stack<Integer> SwitchJumpPatchAddr = new Stack<>();

	private int forCondStartAddr = 0;
	private int forAfterCondPatchAddr = 0;
	private Stack<Integer> switchCounter = new Stack<>();

	private Map<Struct, Integer> classTVFAddrMap = new LinkedHashMap<>();
	private List<Obj> ovoTrebaDaUpecujem = new ArrayList<>();
	private List<Integer> odjeTrebaDaUpecujem = new ArrayList<>();
	private List<Struct> klaseZaUpecavanje = new ArrayList<>();

	private int initTVFAddr = 0;
	private int mainPc = 0;
	private int patchJumpToMain = 0;

	private int currentStaticAddr = 0;
	
	private Struct currentClass = null;

	public int getMainPc() {
		return mainPc;
	}

	public int getDataSize() {
		return currentStaticAddr;
	}

	private boolean isCurrentClassMemberMethod(Obj methodObj){
		if(currentClass == null || methodObj == null || methodObj.getKind() != Obj.Meth) return false;
		for(Obj member : currentClass.getMembers()){
			if(member == methodObj) return true;
		}
		return false;
	}

	private boolean classExists(String programName){
		Obj obj = Tab.find(programName);
		for(Obj o : obj.getLocalSymbols()){
			if(o.getKind() == Obj.Type && o.getType().getKind() == Struct.Class){
				return true;
			}
		}
		return false;
	}

	private List<Struct> getAllClasses(String programName){
		List<Struct> classes = new ArrayList<>();
		Obj obj = Tab.find(programName);
		for(Obj o : obj.getLocalSymbols()){
			if(o.getKind() == Obj.Type && o.getType().getKind() == Struct.Class){
				classes.add(o.getType());
			}
		}
		return classes;
	}

	private void metniTupavuAdresu(int pc, int addr){
		Code.buf[pc++] = (byte)(addr>>24);
		Code.buf[pc++] = (byte)(addr>>16);
		Code.buf[pc++] = (byte)(addr>>8);
		Code.buf[pc] = (byte)(addr);
	}

	private int resolveMethodAddressFromHierarchy(Struct ownerClass, Obj methodObj){
		if(methodObj == null) return 0;
		if(methodObj.getAdr() != 0) return methodObj.getAdr();

		Struct parent = ownerClass != null ? ownerClass.getElemType() : null;
		while(parent != null){
			for(Obj parentMember : parent.getMembers()){
				if(parentMember.getKind() == Obj.Meth && parentMember.getName().equals(methodObj.getName())){
					if(parentMember.getAdr() != 0) return parentMember.getAdr();
					break;
				}
			}
			parent = parent.getElemType();
		}

		return 0;
	}

	public void visit(Program program) {
		for(int i = 0; i < ovoTrebaDaUpecujem.size(); i++){
			int methodAddr = resolveMethodAddressFromHierarchy(klaseZaUpecavanje.get(i), ovoTrebaDaUpecujem.get(i));
			metniTupavuAdresu(odjeTrebaDaUpecujem.get(i), methodAddr);
		}

		if(classExists(program.getProgName().getPName())){
			mainPc = initTVFAddr;
		}

	}

	public void visit(ProgName progName) {
		currentStaticAddr = numOfStaticVars;
		if(classExists(progName.getPName())){
			initTVFAddr = Code.pc;
			List<Struct> classes = getAllClasses(progName.getPName());
			for(Struct struct : classes){
				classTVFAddrMap.put(struct, currentStaticAddr);
				for(Obj method : struct.getMembers()){
					if(method.getKind() == Obj.Meth){
						for(char c : method.getName().toCharArray()){
							Code.loadConst(c);
							Code.put(Code.putstatic);
							Code.put2(currentStaticAddr);
							currentStaticAddr++;
						}
						Code.loadConst(-1);
						Code.put(Code.putstatic);
						Code.put2(currentStaticAddr);
						currentStaticAddr++;

						Code.put(Code.const_);
						Code.pc = Code.pc + 4;
						ovoTrebaDaUpecujem.add(method);
						odjeTrebaDaUpecujem.add(Code.pc - 4);
						klaseZaUpecavanje.add(struct);

						Code.put(Code.putstatic);
						Code.put2(currentStaticAddr);
						currentStaticAddr++;

					}
				}
				Code.loadConst(-2);
				Code.put(Code.putstatic);
				Code.put2(currentStaticAddr);
				currentStaticAddr++;
			}
			Code.putJump(0);
			patchJumpToMain = Code.pc - 2;
		}

	}

	public void visit(ClassName className) {
		currentClass = className.struct;
	}

	public void visit(ClassDecl classDecl) {
		currentClass = null;
	}

	public void visit(DesignatorIdent designatorIdent) {

		if(currentClass != null && designatorIdent.obj != null && designatorIdent.obj.getKind() == Obj.Fld){
			Code.load(new Obj(Obj.Var, "this", currentClass, 0, 1));
			if(designatorIdent.getParent().getParent() instanceof DesignatorLeftSide){
				return;
			}
			Obj o = designatorIdent.obj;
			Code.load(new Obj(o.getKind(), o.getName(), o.getType(), o.getAdr() + 1, o.getLevel()));
			return;
		}

		if(designatorIdent.getParent().getParent() instanceof DesignatorLeftSide){
			return;
		}

		int kind = designatorIdent.obj.getKind();
		if(kind != Obj.Meth && kind != Obj.Type)
			Code.load(designatorIdent.obj);
	}
	
	public void visit(DesignatorField designatorField) {
		if(designatorField.getParent().getParent() instanceof DesignatorLeftSide){
			return;
		}
		Obj obj = designatorField.obj;
		
		if(obj.getKind() == Obj.Meth) return;

		if(obj.getKind() == Obj.Con) {
			Code.load(obj);
			return;
		}

		Code.load(new Obj(obj.getKind(), obj.getName(), obj.getType(), obj.getAdr() + 1, obj.getLevel()));
	}

	public void visit(DesignatorLength designatorLength) {
		Code.put(Code.arraylength);
	}

	public void visit(DesignatorArray designatorArray) {
		if(designatorArray.getParent().getParent() instanceof DesignatorLeftSide){
			return;
		}

		Code.load(designatorArray.obj);
	}

	public void visit(NumConst numConst) {
		Code.loadConst(numConst.getNumVal());
	}

	public void visit(DesignatorFuncCall designatorFuncCall) {
		if(isCurrentClassMemberMethod(designatorFuncCall.obj)){
			Code.load(new Obj(Obj.Var, "this", currentClass, 0, 1));
		}
	}

	public void visit(DesignatorProcCallIsClass designatorProcCallIsClass) {
		if(isCurrentClassMemberMethod(designatorProcCallIsClass.obj)){
			Code.load(new Obj(Obj.Var, "this", currentClass, 0, 1));
		}
	}


	public void visit(FuncCallNoPars funcCallNoPars) {
		DesignatorList designatorList = funcCallNoPars.getDesignatorFuncCall().getDesignator().getDesignatorList();
		if(designatorList instanceof DesignatorField){
			DesignatorField designatorField = (DesignatorField) designatorList;
			designatorField.getDesignatorList().traverseBottomUp(this);
			Code.put(Code.getfield);
			Code.put2(0);
			Code.put(Code.invokevirtual);
			for(char c : designatorField.getName().toCharArray()){
				Code.put4(c);
			}
			Code.put4(-1);
		}
		else{
			DesignatorIdent designatorIdent = (DesignatorIdent) designatorList;
			if(designatorIdent.getName().equals("len")){
				Code.put(Code.arraylength);
			}
			else if (!designatorIdent.getName().equals("ord") 
				&& !designatorIdent.getName().equals("chr")){
				if(isCurrentClassMemberMethod(designatorIdent.obj)){
					Code.load(new Obj(Obj.Var, "this", currentClass, 0, 1));
					Code.put(Code.getfield);
					Code.put2(0);
					Code.put(Code.invokevirtual);
					for(char c : designatorIdent.getName().toCharArray()){
						Code.put4(c);
					}
					Code.put4(-1);
					return;
				}
				Code.put(Code.call);
				Code.put2(designatorIdent.obj.getAdr() - Code.pc + 1);
			}
		}
	}

	public void visit(FuncCall funcCall){
		DesignatorList designatorList = funcCall.getDesignatorFuncCall().getDesignator().getDesignatorList();
		if(designatorList instanceof DesignatorField){
			DesignatorField designatorField = (DesignatorField) designatorList;
			designatorField.getDesignatorList().traverseBottomUp(this);
			Code.put(Code.getfield);
			Code.put2(0);
			Code.put(Code.invokevirtual);
			for(char c : designatorField.getName().toCharArray()){
				Code.put4(c);
			}
			Code.put4(-1);
		}
		else{
			DesignatorIdent designatorIdent = (DesignatorIdent) designatorList;
			if(designatorIdent.getName().equals("len")){
				Code.put(Code.arraylength);
			}
			else if (!designatorIdent.getName().equals("ord") 
				&& !designatorIdent.getName().equals("chr")){
				if(isCurrentClassMemberMethod(designatorIdent.obj)){
					Code.load(new Obj(Obj.Var, "this", currentClass, 0, 1));
					Code.put(Code.getfield);
					Code.put2(0);
					Code.put(Code.invokevirtual);
					for(char c : designatorIdent.getName().toCharArray()){
						Code.put4(c);
					}
					Code.put4(-1);
					return;
				}
				Code.put(Code.call);
				Code.put2(designatorIdent.obj.getAdr() - Code.pc + 1);
			}
		}
		
	}

	public void visit(CharConst charConst) {
		Code.loadConst(charConst.getCharVal());
	}

	public void visit(BoolConst boolConst) {
		Code.loadConst(boolConst.getBoolVal());
	}

	public void visit(NewObj newObj) {
		Code.put(Code.new_);
		Code.put2((newObj.getType().struct.getNumberOfFields() + 1) * 4);
		Code.put(Code.dup);
		Code.loadConst(classTVFAddrMap.get(newObj.getType().struct));// pokazivac na tabelu virtuelnih metoda
		Code.put(Code.putfield);
		Code.put2(0);
	}

	public void visit(NewArray newArray) {
		Code.put(Code.newarray);
		if(newArray.getType().struct.getKind() == Struct.Char){
			Code.put(0);
		}
		else{
			Code.put(1);
		}
	}

	public void visit(MulExpr mulExpr) {
		if(mulExpr.getMulop() instanceof TimesOp){
			Code.put(Code.mul);
		}
		else if(mulExpr.getMulop() instanceof DivOp){
			Code.put(Code.div);
		}
		else if(mulExpr.getMulop() instanceof ModOp){
			Code.put(Code.rem);
		}
	}

	public void visit(ExprWithOptMinus exprWithOptMinus){
		if(exprWithOptMinus.getOptMinus() instanceof HasMinus){
			Code.put(Code.neg);
		}
	}

	public void visit(AddExpr addExpr){
		if(addExpr.getAddop() instanceof PlusOp){
			Code.put(Code.add);
		}
		else if(addExpr.getAddop() instanceof SubOp){
			Code.put(Code.sub);
		}
	}

	public void visit(SimpleCondFact simpleCondFact){
		Code.loadConst(0);
		Code.putFalseJump(Code.ne, 0);
		skipCondFact.push(Code.pc - 2);
	}

	public void visit(RelopCondFact relopCondFact){
		if(relopCondFact.getRelop() instanceof Equalsop){
			Code.putFalseJump(Code.eq, 0);
			skipCondFact.push(Code.pc - 2);
		}
		else if(relopCondFact.getRelop() instanceof Notequalsop){
			Code.putFalseJump(Code.ne, 0);
			skipCondFact.push(Code.pc - 2);
		}
		else if(relopCondFact.getRelop() instanceof Greaterop){
			Code.putFalseJump(Code.gt, 0);
			skipCondFact.push(Code.pc - 2);
		}
		else if(relopCondFact.getRelop() instanceof Greaterequalop){
			Code.putFalseJump(Code.ge, 0);
			skipCondFact.push(Code.pc - 2);
		}
		else if(relopCondFact.getRelop() instanceof Lessop){
			Code.putFalseJump(Code.lt, 0);
			skipCondFact.push(Code.pc - 2);
		}
		else if(relopCondFact.getRelop() instanceof Lessequalop){
			Code.putFalseJump(Code.le, 0);
			skipCondFact.push(Code.pc - 2);
		}
	}

	public void visit(CondTerm condTerm){
		Code.putJump(0);
		skipCondition.push(Code.pc - 2);
		while(!skipCondFact.isEmpty()){
			Code.fixup(skipCondFact.pop());
		}
	}

	public void visit(Condition condition){
		Code.putJump(0);
		skipThen.push(Code.pc - 2);
		while(!skipCondition.isEmpty()){
			Code.fixup(skipCondition.pop());
		}
	}

	public void visit(NoElseClause noElseClause){
		Code.fixup(skipThen.pop());
	}

	public void visit(IfElseMark ifElseMark){
		Code.putJump(0);
		skipElse.push(Code.pc - 2);
		Code.fixup(skipThen.pop());
	}

	public void visit(ElseClause elseClause){
		Code.fixup(skipElse.pop());
	}

	public void visit(TernThenDone ternThenDone){
		Code.putJump(0);
		skipElse.push(Code.pc - 2);
		Code.fixup(skipThen.pop());
	}

	public void visit(TernaryExpr ternaryExpr){
		Code.fixup(skipElse.pop());
	}

	public void visit(Decrement decrement){
		Code.put(Code.const_1);
		Code.put(Code.sub);
		Code.store(decrement.getDesignator().obj);
	}

	public void visit(Increment increment){
		Code.put(Code.const_1);
		Code.put(Code.add);
		Code.store(increment.getDesignator().obj);
	}

	public void visit(ProcCall procCall){
		DesignatorList designatorList = procCall.getDesignatorProcCallIsClass().getDesignator().getDesignatorList();
		if(designatorList instanceof DesignatorField){
			DesignatorField designatorField = (DesignatorField) designatorList;
			designatorField.getDesignatorList().traverseBottomUp(this);
			Code.put(Code.getfield);
			Code.put2(0);
			Code.put(Code.invokevirtual);
			for(char c : designatorField.getName().toCharArray()){
				Code.put4(c);
			}
			Code.put4(-1);
		}
		else{
			DesignatorIdent designatorIdent = (DesignatorIdent) designatorList;
			if(designatorIdent.getName().equals("len")){
				Code.put(Code.arraylength);
			}
			else if (!designatorIdent.getName().equals("ord") 
				&& !designatorIdent.getName().equals("chr")){

				if(isCurrentClassMemberMethod(designatorIdent.obj)){
					Code.load(new Obj(Obj.Var, "this", currentClass, 0, 1));
					Code.put(Code.getfield);
					Code.put2(0);
					Code.put(Code.invokevirtual);
					for(char c : designatorIdent.getName().toCharArray()){
						Code.put4(c);
					}
					Code.put4(-1);
					return;
				}
				Code.put(Code.call);
				Code.put2(designatorIdent.obj.getAdr() - Code.pc + 1);
			}
		}
	}

	public void visit(ProcCallPars procCallPars){
		DesignatorList designatorList = procCallPars.getDesignatorProcCallIsClass().getDesignator().getDesignatorList();
		if(designatorList instanceof DesignatorField){
			DesignatorField designatorField = (DesignatorField) designatorList;
			designatorField.getDesignatorList().traverseBottomUp(this);
			Code.put(Code.getfield);
			Code.put2(0);
			Code.put(Code.invokevirtual);
			for(char c : designatorField.getName().toCharArray()){
				Code.put4(c);
			}
			Code.put4(-1);
		}
		else{
			DesignatorIdent designatorIdent = (DesignatorIdent) designatorList;
			if(designatorIdent.getName().equals("len")){
				Code.put(Code.arraylength);
			}
			else if (!designatorIdent.getName().equals("ord") 
				&& !designatorIdent.getName().equals("chr")){
				if(isCurrentClassMemberMethod(designatorIdent.obj)){
					Code.load(new Obj(Obj.Var, "this", currentClass, 0, 1));
					Code.put(Code.getfield);
					Code.put2(0);
					Code.put(Code.invokevirtual);
					for(char c : designatorIdent.getName().toCharArray()){
						Code.put4(c);
					}
					Code.put4(-1);

					return;
				}
				Code.put(Code.call);
				Code.put2(designatorIdent.obj.getAdr() - Code.pc + 1);
			}
		}
	}

	public void visit(Assignment assignment){
		if(assignment.getDesignatorLeftSide().getDesignator().obj.getKind() == Obj.Fld){
			Obj o = assignment.getDesignatorLeftSide().getDesignator().obj;
			Code.store(new Obj(o.getKind(), o.getName(), o.getType(), o.getAdr() + 1, o.getLevel()));
		}
		else
			Code.store(assignment.getDesignatorLeftSide().getDesignator().obj);
	}

	public void visit(DesignatorStmt designatorStmt){
		if(designatorStmt.getDesignatorStatement() instanceof ProcCall){
			ProcCall procCall = (ProcCall) designatorStmt.getDesignatorStatement();
			if(procCall.getDesignatorProcCallIsClass().obj.getType() != Tab.noType){
				Code.put(Code.pop);
			}
		}
		else if (designatorStmt.getDesignatorStatement() instanceof ProcCallPars){
			ProcCallPars procCallPars = (ProcCallPars) designatorStmt.getDesignatorStatement();
			if(procCallPars.getDesignatorProcCallIsClass().obj.getType() != Tab.noType){
				Code.put(Code.pop);
			}
		}
	}

	public void visit(PrintStmt printStmt){
		Code.loadConst(4);
		if(printStmt.getExpr().struct.getKind() == Struct.Char){
			Code.put(Code.bprint);
		}
		else{
			Code.put(Code.print);
		}
	}

	public void visit(PrintStmtWithWidth printStmtWithWidth){
		if(printStmtWithWidth.getExpr().struct.getKind() == Struct.Char){
			Code.put(Code.bprint);
		}
		else{
			Code.put(Code.print);
		}
	}

	public void visit(ReadStmt readStmt){
		if(readStmt.getDesignatorLeftSide().obj.getType().getKind() == Struct.Char){
			Code.put(Code.bread);
		}
		else{
			Code.put(Code.read);
		}
		Code.store(readStmt.getDesignatorLeftSide().obj);
	}

	public void visit(ReturnExpr returnExpr){
		Code.put(Code.exit);
		Code.put(Code.return_);
	}

	public void visit(ReturnStmt returnStmt){
		Code.put(Code.exit);
		Code.put(Code.return_);
	}

	public void visit(MethodDecl methodDecl){
		if(methodDecl.getMethodTypeName() instanceof TypeMethodTypeName) {
			Code.put(Code.trap);
			Code.put(1);
		}
		else {
			Code.put(Code.exit);
			Code.put(Code.return_);
		}
	}

	public void visit(TypeMethodTypeName typeMethodTypeName){
		typeMethodTypeName.obj.setAdr(Code.pc);
		Code.put(Code.enter);
		int paramCount = typeMethodTypeName.obj.getLevel();
		for(Obj obj : typeMethodTypeName.obj.getLocalSymbols()){
			if(obj.getName().equals("this")){
				paramCount++;
				break;
			}
		}
		Code.put(paramCount);
		Code.put(typeMethodTypeName.obj.getLocalSymbols().size());
	}

	public void visit(VoidMethodTypeName voidMethodTypeName){
		voidMethodTypeName.obj.setAdr(Code.pc);
		if(voidMethodTypeName.getMethName().equals("main")){
			mainPc = Code.pc;
			if(patchJumpToMain != 0){
				Code.fixup(patchJumpToMain);
			}
		}
		Code.put(Code.enter);
		int paramCount = voidMethodTypeName.obj.getLevel();
		for(Obj obj : voidMethodTypeName.obj.getLocalSymbols()){
			if(obj.getName().equals("this")){
				paramCount++;
				break;
			}
		}
		Code.put(paramCount);
		Code.put(voidMethodTypeName.obj.getLocalSymbols().size());
	}

	public void visit(ForCondMark forCondMark){
		forCondStartAddr = Code.pc;
	}

	public void visit(ForAfterCondMark forAfterCondMark){
		Code.putJump(0);
		forAfterCondPatchAddr = Code.pc - 2;
	}

	public void visit(ForPostMark forPostMark){
		forInc.push(Code.pc);
	}

	public void visit(ForBodyMark forBodyMark){
		Code.putJump(forCondStartAddr);
		forCondStartAddr = 0;
		Code.fixup(forAfterCondPatchAddr);
		forAfterCondPatchAddr = 0;

		Break.push(new ArrayList<>());
		switchCounter.push(0);
	}

	public void visit(ForStmt forStmt){
		Code.putJump(forInc.pop());
		Code.fixup(skipThen.pop());
		List<Integer> breakList = Break.pop();
		for(Integer breakAddr : breakList){
			Code.fixup(breakAddr);
		}
		switchCounter.pop();
	}

	public void visit(ContinueStmt continueStmt){
		int currentSwitchCounter = switchCounter.isEmpty() ? 0 : switchCounter.peek();
		for(int i = 0; i < currentSwitchCounter; i++){
			Code.put(Code.pop);
		}
		Code.putJump(forInc.peek());
	}
	
	public void visit(BreakStmt breakStmt){
		Code.putJump(0);
		Break.peek().add(Code.pc - 2);
	}

	public void visit(SwitchStartMark switchStartMark){
		Code.putJump(0);
		SwitchJumpPatchAddr.push(Code.pc - 2);

		SwitchCaseAddr.push(new ArrayList<>());
		SwitchCaseNumber.push(new ArrayList<>());
		switchCounter.push(switchCounter.pop() + 1);

		Break.push(new ArrayList<>());
	}

	public void visit(CaseNumber caseNumber){
		SwitchCaseAddr.peek().add(Code.pc);
		SwitchCaseNumber.peek().add(caseNumber.getCaseVal());
	}

	public void visit(SwitchStmt switchStmt){
		Code.putJump(0);
		int skipCaseJumps = Code.pc - 2;

		Code.fixup(SwitchJumpPatchAddr.pop());

		List<Integer> caseAddrList = SwitchCaseAddr.pop();
		List<Integer> caseNumberList = SwitchCaseNumber.pop();

		for(int i = 0; i < caseAddrList.size(); i++){

			Code.put(Code.dup);
			Code.loadConst(caseNumberList.get(i));

			Code.putFalseJump(Code.ne, caseAddrList.get(i));
			
		}

		Code.fixup(skipCaseJumps);

		List<Integer> breakList = Break.pop();
		for(Integer breakAddr : breakList){
			Code.fixup(breakAddr);
		}

		Code.put(Code.pop);

		switchCounter.push(switchCounter.pop() - 1);
	}

}
