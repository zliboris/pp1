/**
 * 
 */
package rs.ac.bg.etf.pp1;

import java.util.IdentityHashMap;
import java.util.Map;

import rs.etf.pp1.symboltable.concepts.Obj;
import rs.etf.pp1.symboltable.concepts.Scope;
import rs.etf.pp1.symboltable.concepts.Struct;

import rs.etf.pp1.symboltable.visitors.SymbolTableVisitor;

/**
 * @author nemanja.kojic
 *
 */
public class DumpVisitor extends SymbolTableVisitor {
	private static final boolean USE_LEGACY_VAR_TYPE_DUMP = false;

	protected StringBuilder output = new StringBuilder();
	protected final String indent = "   ";
	protected StringBuilder currentIndent = new StringBuilder();
	protected final Map<Struct, String> structTypeNames = new IdentityHashMap<>();
	
	protected void nextIndentationLevel() {
		currentIndent.append(indent);
	}
	
	protected void previousIndentationLevel() {
		if (currentIndent.length() > 0)
			currentIndent.setLength(currentIndent.length()-indent.length());
	}
	
	
	/* (non-Javadoc)
	 * @see rs.etf.pp1.symboltable.test.SymbolTableVisitor#visitObjNode(symboltable.Obj)
	 */
	@Override
	public void visitObjNode(Obj objToVisit) {
		//output.append("[");
		switch (objToVisit.getKind()) {
		case Obj.Con:  output.append("Con "); break;
		case Obj.Var:  output.append("Var "); break;
		case Obj.Type: output.append("Type "); break;
		case Obj.Meth: output.append("Meth "); break;
		case Obj.Fld:  output.append("Fld "); break;
		case Obj.Prog: output.append("Prog "); break;
		}
		
		output.append(objToVisit.getName());
		output.append(": ");

		if (objToVisit.getKind() == Obj.Type) {
			Struct objType = objToVisit.getType();
			if (objType != null && (objType.getKind() == Struct.Class || objType.getKind() == Struct.Interface || objType.getKind() == Struct.Enum)) {
				structTypeNames.put(objType, objToVisit.getName());
			}
		}
		
		if ((Obj.Var == objToVisit.getKind()) && "this".equalsIgnoreCase(objToVisit.getName()))
			output.append("");
		else if (!USE_LEGACY_VAR_TYPE_DUMP && (objToVisit.getKind() == Obj.Var || objToVisit.getKind() == Obj.Fld))
			appendShortTypeNameForVarOrFld(objToVisit.getType());
		else
			objToVisit.getType().accept(this);
		
		output.append(", ");
		output.append(objToVisit.getAdr());
		output.append(", ");
		output.append(objToVisit.getLevel() + " ");
				
		if (objToVisit.getKind() == Obj.Prog || objToVisit.getKind() == Obj.Meth) {
			output.append("\n");
			nextIndentationLevel();
		}
		

		for (Obj o : objToVisit.getLocalSymbols()) {
			output.append(currentIndent.toString());
			o.accept(this);
			output.append("\n");
		}
		
		if (objToVisit.getKind() == Obj.Prog || objToVisit.getKind() == Obj.Meth) 
			previousIndentationLevel();

		//output.append("]");
		
	}

			private void appendShortTypeNameForVarOrFld(Struct type) {
				if (type == null) {
					output.append("notype");
					return;
				}

				switch (type.getKind()) {
				case Struct.Class:
				case Struct.Interface:
				case Struct.Enum:
					output.append(resolveStructTypeName(type));
					return;
				case Struct.Array:
					output.append("Arr of ");
					Struct elemType = type.getElemType();
					if (elemType != null && (elemType.getKind() == Struct.Class || elemType.getKind() == Struct.Interface || elemType.getKind() == Struct.Enum)) {
						output.append(resolveStructTypeName(elemType));
						return;
					}
					if (elemType != null) {
						elemType.accept(this);
						return;
					}
					output.append("notype");
					return;
				default:
					break;
				}

				type.accept(this);
			}

			private String resolveStructTypeName(Struct struct) {
				String mappedName = structTypeNames.get(struct);
				if (mappedName != null) {
					return mappedName;
				}

				switch (struct.getKind()) {
				case Struct.Class:
					return "Class";
				case Struct.Interface:
					return "AbstractClass";
				case Struct.Enum:
					return "Enum";
				default:
					return "notype";
				}
			}

	/* (non-Javadoc)
	 * @see rs.etf.pp1.symboltable.test.SymbolTableVisitor#visitScopeNode(symboltable.Scope)
	 */
	@Override
	public void visitScopeNode(Scope scope) {
		for (Obj o : scope.values()) {
			o.accept(this);
			output.append("\n");
		}
	}

	/* (non-Javadoc)
	 * @see rs.etf.pp1.symboltable.test.SymbolTableVisitor#visitStructNode(symboltable.Struct)
	 */
	@Override
	public void visitStructNode(Struct structToVisit) {
		switch (structToVisit.getKind()) {
		case Struct.None:
			output.append("notype");
			break;
		case Struct.Int:
			output.append("int");
			break;
		case Struct.Char:
			output.append("char");
			break;
		case Struct.Bool:
			output.append("bool");
			break;
		case Struct.Enum:
			output.append("Enum");
			break;
		case Struct.Array:
			output.append("Arr of ");
			
			switch (structToVisit.getElemType().getKind()) {
			case Struct.None:
				output.append("notype");
				break;
			case Struct.Int:
				output.append("int");
				break;
			case Struct.Char:
				output.append("char");
				break;
			case Struct.Bool:
				output.append("bool");
				break;
			case Struct.Enum:
				output.append("Enum");
				break;
			case Struct.Class:
				output.append("Class");
				break;
			case Struct.Interface:
				output.append("AbstractClass");
				break;
			}
			break;
		case Struct.Class:
			output.append("Class [");
			if (!structToVisit.getMembers().isEmpty()) {
				output.append("\n");
				nextIndentationLevel();
				for (Obj obj : structToVisit.getMembers()) {
					output.append(currentIndent.toString());
					obj.accept(this);
					output.append("\n");
				}
				previousIndentationLevel();
				output.append(currentIndent.toString());
			}
			output.append("]");
			break;
		case Struct.Interface:
			output.append("AbstractClass [");
			if (!structToVisit.getMembers().isEmpty()) {
				output.append("\n");
				nextIndentationLevel();
				for (Obj obj : structToVisit.getMembers()) {
					output.append(currentIndent.toString());
					obj.accept(this);
					output.append("\n");
				}
				previousIndentationLevel();
				output.append(currentIndent.toString());
			}
			output.append("]");
			break;
		}

	}

	public String getOutput() {
		return output.toString();
	}
	
	
}
