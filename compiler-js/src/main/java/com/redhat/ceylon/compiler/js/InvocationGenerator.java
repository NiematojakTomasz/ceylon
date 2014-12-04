package com.redhat.ceylon.compiler.js;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.redhat.ceylon.compiler.typechecker.model.Constructor;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Functional;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.UnionType;
import com.redhat.ceylon.compiler.typechecker.model.Util;
import com.redhat.ceylon.compiler.typechecker.model.Value;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;

/** Generates js code for invocation expression (named and positional). */
public class InvocationGenerator {

    private final GenerateJsVisitor gen;
    private final JsIdentifierNames names;
    private final RetainedVars retainedVars;

    InvocationGenerator(GenerateJsVisitor owner, JsIdentifierNames names, RetainedVars rv) {
        gen = owner;
        this.names = names;
        retainedVars = rv;
    }

    void generateInvocation(Tree.InvocationExpression that) {
        final Tree.Primary typeArgSource = that.getPrimary();
        if (that.getNamedArgumentList()!=null) {
            Tree.NamedArgumentList argList = that.getNamedArgumentList();
            if (gen.isInDynamicBlock() && typeArgSource instanceof Tree.MemberOrTypeExpression
                    && ((Tree.MemberOrTypeExpression)typeArgSource).getDeclaration() == null) {
                final String fname = names.createTempVariable();
                gen.out("(", fname, "=");
                //Call a native js constructor passing a native js object as parameter
                typeArgSource.visit(gen);
                gen.out(",", fname, ".$$===undefined?new ", fname, "(");
                nativeObject(argList);
                gen.out("):", fname, "(");
                nativeObject(argList);
                gen.out("))");
            } else {
                gen.out("(");
                Map<String, String> argVarNames = defineNamedArguments(typeArgSource, argList);
                if (typeArgSource instanceof Tree.BaseMemberExpression) {
                    BmeGenerator.generateBme((Tree.BaseMemberExpression)typeArgSource, gen, true);
                } else {
                    typeArgSource.visit(gen);
                }
                if (typeArgSource instanceof Tree.MemberOrTypeExpression) {
                    Tree.MemberOrTypeExpression mte = (Tree.MemberOrTypeExpression) typeArgSource;
                    if (mte.getDeclaration() instanceof Functional) {
                        Functional f = (Functional) mte.getDeclaration();
                        Tree.TypeArguments targs = null;
                        if (typeArgSource instanceof Tree.StaticMemberOrTypeExpression) {
                            targs = ((Tree.StaticMemberOrTypeExpression)typeArgSource).getTypeArguments();
                        }
                        applyNamedArguments(argList, f, argVarNames, gen.getSuperMemberScope(mte)!=null, targs);
                    }
                }
                gen.out(")");
            }
        }
        else {
            final Tree.PositionalArgumentList argList = that.getPositionalArgumentList();
            final Map<TypeParameter, ProducedType> targs;
            if (typeArgSource instanceof Tree.StaticMemberOrTypeExpression) {
                Tree.StaticMemberOrTypeExpression smote = (Tree.StaticMemberOrTypeExpression) typeArgSource;
                if (smote.getDeclaration() instanceof Constructor &&
                        !((Functional)smote.getDeclaration().getContainer()).getTypeParameters().isEmpty()) {
                    targs = smote.getTarget().getTypeArguments();
                } else if (smote.getDeclaration() instanceof Functional) {
                    targs = TypeUtils.matchTypeParametersWithArguments(
                            ((Functional)smote.getDeclaration()).getTypeParameters(),
                            smote.getTypeArguments().getTypeModels());
                    if (targs == null) {
                        gen.out("/*TARGS != TPARAMS!!!! WTF?????*/");
                    }
                } else {
                    targs = null;
                }
            } else {
                targs = null;
            }
            if (gen.isInDynamicBlock() && typeArgSource instanceof Tree.BaseTypeExpression
                    && ((Tree.BaseTypeExpression)typeArgSource).getDeclaration() == null) {
                gen.out("(");
                //Could be a dynamic object, or a Ceylon one
                //We might need to call "new" so we need to get all the args to pass directly later
                final List<String> argnames = generatePositionalArguments(typeArgSource,
                        argList, argList.getPositionalArguments(), false, true);
                if (!argnames.isEmpty()) {
                    gen.out(",");
                }
                final String fname = names.createTempVariable();
                gen.out(fname,"=");
                typeArgSource.visit(gen);
                String fuckingargs = "";
                if (!argnames.isEmpty()) {
                    fuckingargs = argnames.toString().substring(1);
                    fuckingargs = fuckingargs.substring(0, fuckingargs.length()-1);
                }
                gen.out(",", fname, ".$$===undefined?new ", fname, "(", fuckingargs, "):", fname, "(", fuckingargs, "))");
                //TODO we lose type args for now
                return;
            } else {
                if (typeArgSource instanceof Tree.BaseMemberExpression) {
                    final Tree.BaseMemberExpression _bme = (Tree.BaseMemberExpression)typeArgSource;
                    if (gen.isInDynamicBlock() && _bme.getDeclaration() != null &&
                            "ceylon.language::print".equals(_bme.getDeclaration().getQualifiedNameString())) {
                        Tree.PositionalArgument printArg =  that.getPositionalArgumentList().getPositionalArguments().get(0);
                        if (printArg.getTypeModel().isUnknown()) {
                            gen.out(gen.getClAlias(), "pndo$(/*DYNAMIC arg*/"); //#397
                            printArg.visit(gen);
                            gen.out(")");
                            return;
                        }
                    }
                    BmeGenerator.generateBme(_bme, gen, true);
                } else {
                    typeArgSource.visit(gen);
                }
                if (gen.opts.isOptimize() && (gen.getSuperMemberScope(typeArgSource) != null)) {
                    gen.out(".call(this");
                    if (!argList.getPositionalArguments().isEmpty()) {
                        gen.out(",");
                    }
                } else {
                    gen.out("(");
                }
                //Check if args have params
                boolean fillInParams = !argList.getPositionalArguments().isEmpty();
                for (Tree.PositionalArgument arg : argList.getPositionalArguments()) {
                    fillInParams &= arg.getParameter() == null;
                }
                if (fillInParams) {
                    //Get the callable and try to assign params from there
                    ProducedType callable = typeArgSource.getTypeModel().getSupertype(
                            that.getUnit().getCallableDeclaration());
                    if (callable != null) {
                        //This is a tuple with the arguments to the callable
                        //(can be union with empty if first param is defaulted)
                        ProducedType callableArgs = callable.getTypeArgumentList().get(1);
                        boolean isUnion=false;
                        if (callableArgs.getDeclaration() instanceof UnionType) {
                            if (callableArgs.getCaseTypes().size() == 2) {
                                callableArgs = callableArgs.minus(that.getUnit().getEmptyDeclaration().getType());
                            }
                            isUnion=callableArgs.getDeclaration() instanceof UnionType;
                        }
                        //This is the type of the first argument
                        boolean isSequenced = !(isUnion || that.getUnit().getTupleDeclaration().equals(
                                callableArgs.getDeclaration()));
                        ProducedType argtype = isUnion ? callableArgs :
                            callableArgs.getDeclaration() instanceof TypeParameter ? callableArgs :
                            callableArgs.getTypeArgumentList().get(
                                isSequenced ? 0 : 1);
                        Parameter p = null;
                        int c = 0;
                        for (Tree.PositionalArgument arg : argList.getPositionalArguments()) {
                            if (p == null) {
                                p = new Parameter();
                                p.setName("arg"+c);
                                p.setDeclaration(typeArgSource.getTypeModel().getDeclaration());
                                Value v = new Value();
                                v.setContainer(that.getPositionalArgumentList().getScope());
                                v.setType(argtype);
                                p.setModel(v);
                                if (callableArgs == null || isSequenced) {
                                    p.setSequenced(true);
                                } else if (!isSequenced) {
                                    ProducedType next = isUnion ? null : callableArgs.getTypeArgumentList().get(2);
                                    if (next != null && next.getSupertype(that.getUnit().getTupleDeclaration()) == null) {
                                        //It's not a tuple, so no more regular parms. It can be:
                                        //empty|tuple if defaulted params
                                        //empty if no more params
                                        //sequential if sequenced param
                                        if (next.getDeclaration() instanceof UnionType) {
                                            //empty|tuple
                                            callableArgs = next.minus(that.getUnit().getEmptyDeclaration().getType());
                                            isSequenced = !that.getUnit().getTupleDeclaration().equals(
                                                    callableArgs.getDeclaration());
                                            argtype = callableArgs.getTypeArgumentList().get(isSequenced ? 0 : 1);
                                        } else {
                                            //we'll bet on sequential (if it's empty we don't care anyway)
                                            argtype = next;
                                            callableArgs = null;
                                        }
                                    } else {
                                        //If it's a tuple then there are more params
                                        callableArgs = next;
                                        argtype = callableArgs == null ? null : callableArgs.getTypeArgumentList().get(1);
                                    }
                                }
                            }
                            arg.setParameter(p);
                            c++;
                            if (!p.isSequenced()) {
                                p = null;
                            }
                        }
                    }
                }
                generatePositionalArguments(typeArgSource, argList, argList.getPositionalArguments(), false, false);
            }
            if (targs != null && !targs.isEmpty()
                    && typeArgSource instanceof Tree.StaticMemberOrTypeExpression
                    && ((Tree.StaticMemberOrTypeExpression)typeArgSource).getDeclaration() instanceof Functional) {
                if (argList.getPositionalArguments().size() > 0) {
                    gen.out(",");
                }
                Functional bmed = (Functional)((Tree.StaticMemberOrTypeExpression)typeArgSource).getDeclaration();
                //If there are fewer arguments than there are parameters...
                final int argsSize = argList.getPositionalArguments().size();
                int paramArgDiff = ((Functional) bmed).getParameterLists().get(0).getParameters().size() - argsSize;
                if (paramArgDiff > 0) {
                    final Tree.PositionalArgument parg = argsSize > 0 ? argList.getPositionalArguments().get(argsSize-1) : null;
                    if (parg instanceof Tree.Comprehension || parg instanceof Tree.SpreadArgument) {
                        paramArgDiff--;
                    }
                    for (int i=0; i < paramArgDiff; i++) {
                        gen.out("undefined,");
                    }
                }
                if (targs != null && !targs.isEmpty()) {
                    TypeUtils.printTypeArguments(typeArgSource, targs, gen, false, null);
                }
            }
            gen.out(")");
        }
    }

    /** Generates the code to evaluate the expressions in the named argument list and assign them
     * to variables, then returns a map with the parameter names and the corresponding variable names. */
    Map<String, String> defineNamedArguments(Tree.Primary primary, Tree.NamedArgumentList argList) {
        Map<String, String> argVarNames = new HashMap<String, String>();
        for (Tree.NamedArgument arg: argList.getNamedArguments()) {
            Parameter p = arg.getParameter();
            final String paramName;
            if (p == null && gen.isInDynamicBlock()) {
                paramName = arg.getIdentifier().getText();
            } else {
                paramName = arg.getParameter().getName();
            }
            String varName = names.createTempVariable();
            argVarNames.put(paramName, varName);
            retainedVars.add(varName);
            gen.out(varName, "=");
            arg.visit(gen);
            gen.out(",");
        }
        Tree.SequencedArgument sarg = argList.getSequencedArgument();
        if (sarg!=null) {
            String paramName = sarg.getParameter().getName();
            String varName = names.createTempVariable();
            argVarNames.put(paramName, varName);
            retainedVars.add(varName);
            gen.out(varName, "=");
            generatePositionalArguments(primary, argList, sarg.getPositionalArguments(), true, false);
            gen.out(",");
        }
        return argVarNames;
    }

    void applyNamedArguments(Tree.NamedArgumentList argList, Functional func,
                Map<String, String> argVarNames, boolean superAccess, Tree.TypeArguments targs) {
        boolean firstList = true;
        for (com.redhat.ceylon.compiler.typechecker.model.ParameterList plist : func.getParameterLists()) {
            boolean first=true;
            if (firstList && superAccess) {
                gen.out(".call(this");
                if (!plist.getParameters().isEmpty()) { gen.out(","); }
            }
            else {
                gen.out("(");
            }
            for (Parameter p : plist.getParameters()) {
                if (!first) gen.out(",");
                final String vname = argVarNames.get(p.getName());
                if (vname == null) {
                    if (p.isDefaulted()) {
                        gen.out("undefined");
                    } else {
                        gen.out(gen.getClAlias(), "getEmpty()");
                    }
                } else {
                    gen.out(vname);
                }
                first = false;
            }
            if (targs != null && !targs.getTypeModels().isEmpty()) {
                Map<TypeParameter, ProducedType> invargs = TypeUtils.matchTypeParametersWithArguments(
                        func.getTypeParameters(), targs.getTypeModels());
                if (!first) gen.out(",");
                TypeUtils.printTypeArguments(argList, invargs, gen, false, null);
            }
            gen.out(")");
            firstList = false;
        }
    }

    /** Generate a list of PositionalArguments, optionally assigning a variable name to each one
     * and returning the variable names. */
    List<String> generatePositionalArguments(final Tree.Primary primary, final Tree.ArgumentList that,
            final List<Tree.PositionalArgument> args,
            final boolean forceSequenced, final boolean generateVars) {
        if (args.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> argvars = new ArrayList<String>(args.size());
        boolean first=true;
        boolean opened=false;
        ProducedType sequencedType=null;
        for (Tree.PositionalArgument arg : args) {
            Tree.Expression expr;
            final Parameter pd = arg.getParameter();
            if (arg instanceof Tree.ListedArgument) {
                if (!first) gen.out(",");
                expr = ((Tree.ListedArgument) arg).getExpression();
                ProducedType exprType = expr.getTypeModel();
                boolean dyncheck = gen.isInDynamicBlock() && pd != null && !Util.isTypeUnknown(pd.getType())
                        && exprType.containsUnknowns();
                if (forceSequenced || (pd != null && pd.isSequenced())) {
                    if (dyncheck) {
                        //We don't have a real type so get the one declared in the parameter
                        exprType = pd.getType();
                    }
                    if (sequencedType == null) {
                        sequencedType=exprType;
                    } else {
                        ArrayList<ProducedType> cases = new ArrayList<ProducedType>(2);
                        Util.addToUnion(cases, sequencedType);
                        Util.addToUnion(cases, exprType);
                        if (cases.size() > 1) {
                            UnionType ut = new UnionType(that.getUnit());
                            ut.setCaseTypes(cases);
                            sequencedType = ut.getType();
                        } else {
                            sequencedType = cases.get(0);
                        }
                    }
                    if (!opened) {
                        if (generateVars) {
                            final String argvar = names.createTempVariable();
                            argvars.add(argvar);
                            gen.out(argvar, "=");
                        }
                        gen.out("[");
                    }
                    opened=true;
                } else if (generateVars) {
                    final String argvar = names.createTempVariable();
                    argvars.add(argvar);
                    gen.out(argvar, "=");
                }
                final int boxType = pd==null?0:gen.boxUnboxStart(expr.getTerm(), pd.getModel());
                if (dyncheck) {
                    Map<TypeParameter,ProducedType> targs = null;
                    if (primary instanceof Tree.MemberOrTypeExpression) {
                        targs = ((Tree.MemberOrTypeExpression)primary).getTarget().getTypeArguments();
                    }
                    TypeUtils.generateDynamicCheck(expr, pd.getType(), gen, false, targs);
                } else {
                    arg.visit(gen);
                }
                if (boxType == 4) {
                    gen.out(",");
                    //Add parameters
                    describeMethodParameters(expr.getTerm());
                    gen.out(",");
                    TypeUtils.printTypeArguments(arg, arg.getTypeModel().getTypeArguments(), gen, false,
                            arg.getTypeModel().getVarianceOverrides());
                }
                gen.boxUnboxEnd(boxType);
            } else if (arg instanceof Tree.SpreadArgument || arg instanceof Tree.Comprehension) {
                if (arg instanceof Tree.SpreadArgument) {
                    expr = ((Tree.SpreadArgument) arg).getExpression();
                } else {
                    expr = null;
                }
                if (opened) {
                    SequenceGenerator.closeSequenceWithReifiedType(that,
                            TypeUtils.wrapAsIterableArguments(sequencedType), gen);
                    gen.out(".chain(");
                    sequencedType=null;
                } else if (!first) {
                    gen.out(",");
                }
                if (arg instanceof Tree.SpreadArgument) {
                    TypedDeclaration td = pd == null ? null : pd.getModel();
                    int boxType = gen.boxUnboxStart(expr.getTerm(), td);
                    if (boxType == 4) {
                        arg.visit(gen);
                        gen.out(",");
                        describeMethodParameters(expr.getTerm());
                        gen.out(",");
                        TypeUtils.printTypeArguments(arg, arg.getTypeModel().getTypeArguments(), gen, false,
                                arg.getTypeModel().getVarianceOverrides());
                    } else if (pd == null) {
                        if (gen.isInDynamicBlock() && primary instanceof Tree.MemberOrTypeExpression
                                && ((Tree.MemberOrTypeExpression)primary).getDeclaration() == null
                                && arg.getTypeModel() != null && arg.getTypeModel().getDeclaration().inherits((
                                        that.getUnit().getTupleDeclaration()))) {
                            //Spread dynamic parameter
                            ProducedType tupleType = arg.getTypeModel();
                            ProducedType targ = tupleType.getTypeArgumentList().get(2);
                            arg.visit(gen);
                            gen.out(".$_get(0)");
                            int i = 1;
                            while (!targ.getDeclaration().inherits(that.getUnit().getEmptyDeclaration())) {
                                gen.out(",");
                                arg.visit(gen);
                                gen.out(".$_get("+(i++)+")");
                                targ = targ.getTypeArgumentList().get(2);
                            }
                        } else {
                            arg.visit(gen);
                        }
                    } else if (pd.isSequenced()) {
                        arg.visit(gen);
                    } else {
                        final String specialSpreadVar = gen.getNames().createTempVariable();
                        gen.out("(", specialSpreadVar, "=");
                        args.get(args.size()-1).visit(gen);
                        gen.out(".sequence(),");
                        if (pd.isDefaulted()) {
                            gen.out(gen.getClAlias(), "nn$(",
                                    specialSpreadVar, ".$_get(0))?", specialSpreadVar,
                                    ".$_get(0):undefined)");
                        } else {
                            gen.out(specialSpreadVar, ".$_get(0))");
                        }
                        //Find out if there are more params
                        final List<Parameter> moreParams;
                        final Declaration pdd = pd.getDeclaration();
                        boolean found = false;
                        if (pdd instanceof Method) {
                            moreParams = ((Method)pdd).getParameterLists().get(0).getParameters();
                        } else if (pdd instanceof com.redhat.ceylon.compiler.typechecker.model.Class) {
                            moreParams = ((com.redhat.ceylon.compiler.typechecker.model.Class)pdd).getParameterList().getParameters();
                        } else {
                            //Check the parameters of the primary (obviously a callable, so this is a Tuple)
                            List<Parameter> cparms = TypeUtils.convertTupleToParameters(
                                    primary.getTypeModel().getTypeArgumentList().get(1));
                            cparms.remove(0);
                            moreParams = cparms;
                            found = true;
                        }
                        if (moreParams != null) {
                            int c = 1;
                            for (Parameter restp : moreParams) {
                                if (found) {
                                    final String cs=Integer.toString(c++);
                                    if (restp.isDefaulted()) {
                                        gen.out(",", gen.getClAlias(), "nn$(", specialSpreadVar,
                                                ".$_get(", cs, "))?", specialSpreadVar, ".$_get(", cs, "):undefined");
                                    } else {
                                        gen.out(",", specialSpreadVar, ".$_get(", cs, ")");
                                    }
                                } else {
                                    found = restp.equals(pd);
                                }
                            }
                        }
                    }
                    gen.boxUnboxEnd(boxType);
                } else {
                    ((Tree.Comprehension)arg).visit(gen);
                }
                if (opened) {
                    gen.out(",");
                    if (expr == null) {
                        //it's a comprehension
                        TypeUtils.printTypeArguments(that,
                                TypeUtils.wrapAsIterableArguments(arg.getTypeModel()), gen, false, null);
                    } else {
                        ProducedType spreadType = TypeUtils.findSupertype(
                                that.getUnit().getSequentialDeclaration(),
                                expr.getTypeModel());
                        if (spreadType == null) {
                            //Go directly to Iterable
                            spreadType = TypeUtils.findSupertype(that.getUnit().getIterableDeclaration(),
                                    expr.getTypeModel());
                        }
                        TypeUtils.printTypeArguments(that, spreadType.getTypeArguments(), gen, false,
                                spreadType.getVarianceOverrides());
                    }
                    gen.out(")");
                }
                if (arg instanceof Tree.Comprehension) {
                    break;
                }
            }
            first = false;
        }
        if (sequencedType != null) {
            final Map<TypeParameter,ProducedType> seqtargs;
            if (forceSequenced && args.size() > 0) {
                seqtargs = that.getUnit().getNonemptyIterableType(sequencedType).getTypeArguments();
            } else {
                seqtargs = TypeUtils.wrapAsIterableArguments(sequencedType);
            }
            SequenceGenerator.closeSequenceWithReifiedType(primary,
                    seqtargs, gen);
        }
        return argvars;
    }

    /** Generate the code to create a native js object. */
    void nativeObject(Tree.NamedArgumentList argList) {
        final List<Tree.NamedArgument> nargs = argList.getNamedArguments();
        if (argList.getSequencedArgument() == null) {
            gen.out("{");
            boolean first = true;
            for (Tree.NamedArgument arg : nargs) {
                if (first) { first = false; } else { gen.out(","); }
                String argName = arg.getIdentifier().getText();
                if (JsIdentifierNames.isReservedWord(argName)) {
                    gen.out("$_");
                }
                gen.out(argName, ":");
                arg.visit(gen);
            }
            gen.out("}");
        } else {
            String arr = null;
            boolean isComp = false;
            if (nargs.size() > 0) {
                gen.out("function()");
                gen.beginBlock();
                arr = names.createTempVariable();
                gen.out("var ", arr, "=");
            } else {
                isComp = argList.getSequencedArgument().getPositionalArguments().size() == 1
                        && argList.getSequencedArgument().getPositionalArguments().get(0) instanceof Tree.Comprehension;
            }
            if (isComp) {
                gen.out(gen.getClAlias(), "nfor$(");
            } else {
                gen.out("[");
            }
            boolean first = true;
            for (Tree.PositionalArgument arg : argList.getSequencedArgument().getPositionalArguments()) {
                if (first) { first = false; } else { gen.out(","); }
                arg.visit(gen);
            }
            if (isComp) {
                gen.out(")");
            } else {
                gen.out("]");
            }
            if (nargs.size() > 0) {
                gen.endLine(true);
                for (Tree.NamedArgument arg : nargs) {
                    gen.out(arr, ".", arg.getIdentifier().getText(), "=");
                    arg.visit(gen);
                    gen.endLine(true);
                }
                gen.out("return ", arr,";");
                gen.endBlock();
                gen.out("()");
            }
        }
    }

    private void describeMethodParameters(Tree.Term term) {
        Method _m = null;
        if (term instanceof Tree.FunctionArgument) {
            _m = (((Tree.FunctionArgument)term).getDeclarationModel());
        } else if (term instanceof Tree.MemberOrTypeExpression) {
            if (((Tree.MemberOrTypeExpression)term).getDeclaration() instanceof Method) {
                _m = (Method)((Tree.MemberOrTypeExpression)term).getDeclaration();
            }
        } else if (term instanceof Tree.InvocationExpression) {
            TypeUtils.encodeCallableArgumentsAsParameterListForRuntime(term.getTypeModel(), gen);
            return;
        } else {
            gen.out("/*WARNING4 Callable EXPR of type ", term.getClass().getName(), "*/");
        }
        if (_m == null) {
            gen.out("[]");
        } else {
            TypeUtils.encodeParameterListForRuntime(term, _m.getParameterLists().get(0), gen);
        }
    }

}