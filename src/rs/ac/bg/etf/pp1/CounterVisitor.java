package rs.ac.bg.etf.pp1;

import rs.ac.bg.etf.pp1.ast.ArrayFormalParam;
import rs.ac.bg.etf.pp1.ast.ArrayVar;
import rs.ac.bg.etf.pp1.ast.ScalarFormalParam;
import rs.ac.bg.etf.pp1.ast.ScalarVar;
import rs.ac.bg.etf.pp1.ast.VisitorAdaptor;

public class CounterVisitor extends VisitorAdaptor {
	
	protected int count;
	
	public int getCount() {
		return count;
	}
	
	public static class FormParamCounter extends CounterVisitor {

		@Override
		public void visit(ScalarFormalParam scalarFormalParam) {
			count++;
		}

		@Override
		public void visit(ArrayFormalParam arrayFormalParam) {
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
