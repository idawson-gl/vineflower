package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.decompiler.exps.ConstExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.FieldExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.InvocationExprent;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.attr.StructBootstrapMethodsAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.consts.LinkConstant;
import org.jetbrains.java.decompiler.struct.consts.PooledConstant;
import org.jetbrains.java.decompiler.struct.consts.PrimitiveConstant;
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.Arrays;
import java.util.List;

// Handles the java.lang.invoke.ConstantBootstraps bootstraps
public class CondyHelper {

  // TODO: handle other bootstraps (invoke, arrayVarHandle, explicitCast)
  private static final String CONSTANT_BOOTSTRAPS_CLASS = "java/lang/invoke/ConstantBootstraps";

  public static Exprent simplifyCondy(InvocationExprent condyExpr) {
    if (condyExpr.getInvocationTyp() != InvocationExprent.CONSTANT_DYNAMIC) {
      return condyExpr;
    }

    LinkConstant method = condyExpr.getBootstrapMethod();
    if (!CONSTANT_BOOTSTRAPS_CLASS.equals(method.classname)) {
      return condyExpr;
    }

    switch (method.elementname) {
      case "nullConstant":
        // TODO: include target type?
        return new ConstExprent(VarType.VARTYPE_NULL, null, null).setWasCondy(true);
      case "primitiveClass":
        String desc = condyExpr.getName();
        if (desc.length() != 1 || !("ZCBSIJFDV".contains(desc))) {
          break;
        }
        VarType type = new VarType(desc, false);
        return new ConstExprent(VarType.VARTYPE_CLASS, ExprProcessor.getCastTypeName(type), null).setWasCondy(true);
      case "enumConstant":
        String typeName = condyExpr.getExprType().value;
        return new FieldExprent(condyExpr.getName(), typeName, true, null, FieldDescriptor.parseDescriptor("L" + typeName + ";"), null, false, true);
      case "getStaticFinal": {
        List<PooledConstant> constArgs = condyExpr.getBootstrapArguments();
        String fieldType = condyExpr.getExprType().value;
        String ownerClass;
        if (constArgs.size() == 1) {
          PooledConstant ownerName = constArgs.get(0);
          if (ownerName instanceof PrimitiveConstant) {
            ownerClass = ((PrimitiveConstant) ownerName).value.toString();
          } else {
            return condyExpr;
          }
        } else {
          if (condyExpr.getExprType().type != VarType.VARTYPE_OBJECT.type) {
            return condyExpr;
          }
          ownerClass = fieldType;
        }
        return new FieldExprent(condyExpr.getName(), ownerClass, true, null, FieldDescriptor.parseDescriptor(fieldType), null, false, true);
      }
      case "fieldVarHandle":
      case "staticFieldVarHandle": {
        if (!DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_COMPLEX_CONDYS)) {
          return condyExpr;
        }
        boolean isStatic = method.elementname.startsWith("static");
        List<PooledConstant> constArgs = condyExpr.getBootstrapArguments();
        String fieldName = condyExpr.getName();
        if(constArgs.size() != 2 || !(constArgs.get(0) instanceof PrimitiveConstant)) {
          return condyExpr;
        }
        String ownerClass = ((PrimitiveConstant) constArgs.get(0)).getString();
        return constructVarHandle(fieldName, ownerClass, constArgs.get(1), isStatic);
      }
    }
    return condyExpr;
  }

  private static Exprent constructVarHandle(String fieldName, String fieldOwner, PooledConstant fieldType, boolean isStatic) {
    // MethodHandles.lookup().find[Static]VarHandle(fieldOwner.class, fieldName, fieldType.class)
    // with comment
    Exprent lookupExprent = constructLookupExprent();
    VarType ownerClassClass = new VarType(fieldOwner, false);
    Exprent ownerClassConst = new ConstExprent(VarType.VARTYPE_CLASS, ExprProcessor.getCastTypeName(ownerClassClass), null);
    Exprent fieldNameConst = new ConstExprent(VarType.VARTYPE_STRING, fieldName, null);
    Exprent fieldTypeConst;
    if (fieldType instanceof PrimitiveConstant) {
      VarType fieldTypeClass = new VarType(((PrimitiveConstant) fieldType).getString(), false);
      fieldTypeConst = new ConstExprent(VarType.VARTYPE_CLASS, ExprProcessor.getCastTypeName(fieldTypeClass), null);
    } else {
      fieldTypeConst = toCondyExprent((LinkConstant) fieldType);
    }
    return constructFindVarHandleExprent(isStatic, lookupExprent, ownerClassConst, fieldNameConst, fieldTypeConst);
  }

  private static Exprent toCondyExprent(LinkConstant fieldType) {
    Exprent fieldTypeConst;
    // TODO: is this correct in non-trivial cases?
    StructClass cl = (StructClass)DecompilerContext.getProperty(DecompilerContext.CURRENT_CLASS);
    StructBootstrapMethodsAttribute bootstrap = cl.getAttribute(StructGeneralAttribute.ATTRIBUTE_BOOTSTRAP_METHODS);
    LinkConstant bootstrapMethod = null;
    List<PooledConstant> constArgs = null;
    if (bootstrap != null) {
      bootstrapMethod = bootstrap.getMethodReference(fieldType.index1);
      constArgs = bootstrap.getMethodArguments(fieldType.index1);
    }
    InvocationExprent arg = new InvocationExprent(CodeConstants.opc_ldc, fieldType, bootstrapMethod, constArgs, null, null);
    fieldTypeConst = simplifyCondy(arg);
    if (fieldTypeConst instanceof ConstExprent) {
      ((ConstExprent) fieldTypeConst).setWasCondy(false); // comment is redundant
    }
    return fieldTypeConst;
  }

  private static InvocationExprent constructLookupExprent() {
    InvocationExprent exprent = new InvocationExprent();
    exprent.setName("lookup");
    exprent.setClassname("java/lang/invoke/MethodHandles");
    String desc = "()Ljava/lang/invoke/MethodHandles$Lookup;";
    exprent.setStringDescriptor(desc);
    exprent.setDescriptor(MethodDescriptor.parseDescriptor(desc));
    exprent.setFunctype(InvocationExprent.TYP_GENERAL);
    exprent.setStatic(true);
    return exprent;
  }

  private static InvocationExprent constructFindVarHandleExprent(boolean isStatic, Exprent lookup, Exprent ownerClass, Exprent fieldName, Exprent fieldClass) {
    InvocationExprent exprent = new InvocationExprent();
    exprent.setName(isStatic ? "findStaticVarHandle" : "findVarHandle");
    exprent.setClassname("java/lang/invoke/MethodHandles$Lookup");
    String desc = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;";
    exprent.setStringDescriptor(desc);
    exprent.setDescriptor(MethodDescriptor.parseDescriptor(desc));
    exprent.setFunctype(InvocationExprent.TYP_GENERAL);
    exprent.setStatic(false);
    exprent.setInstance(lookup);
    exprent.setLstParameters(Arrays.asList(ownerClass, fieldName, fieldClass));
    return exprent.markWasLazyCondy();
  }
}
