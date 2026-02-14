package rs.ac.bg.etf.pp1;

import rs.ac.bg.etf.pp1.ast.*;

public class CounterVisitor extends VisitorAdaptor {
	
	protected int count;
	
	public int getCount() {
		return count;
	}
	
	public static class FormParamCounter extends CounterVisitor {

		@Override
		public void visit(ScalarFormalParam formalParam) {
			count++;
		}

		@Override
		public void visit(ArrayFormalParam formalParam) {
			count++;
		}		
	}
	
	public static class VarCounter extends CounterVisitor {		
		@Override
		public void visit(ScalarVar scalarVar) {
			count++;
		}

		@Override
		public void visit(ArrayVar arrayVar) {
			count++;
		}
	}
}
