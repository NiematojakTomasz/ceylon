/*
 * Copyright Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the authors tag. All rights reserved.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License version 2.
 * 
 * This particular file is subject to the "Classpath" exception as provided in the 
 * LICENSE file that accompanied this code.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package com.redhat.ceylon.compiler.java.codegen;

import java.util.List;

import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Functional;
import com.redhat.ceylon.compiler.typechecker.model.FunctionalParameter;
import com.redhat.ceylon.compiler.typechecker.model.IntersectionType;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ParameterList;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.Setter;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.UnionType;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AnyAttribute;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AnyMethod;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AttributeArgument;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AttributeDeclaration;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AttributeSetterDefinition;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.ClassDefinition;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.FunctionArgument;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.Variable;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

public abstract class BoxingDeclarationVisitor extends Visitor {

    protected abstract boolean isCeylonBasicType(ProducedType type);
    protected abstract boolean isNothing(ProducedType type);
    protected abstract boolean isObject(ProducedType type);

    @Override
    public void visit(FunctionArgument that) {
        super.visit(that);
        boxMethod(that.getDeclarationModel());
    }
    
    @Override
    public void visit(AnyMethod that) {
        super.visit(that);
        boxMethod(that.getDeclarationModel());
        rawTypedDeclaration(that.getDeclarationModel());
    }

    private void rawTypedDeclaration(TypedDeclaration decl) {
        // deal with invalid input
        if(decl == null)
            return;

        ProducedType type = decl.getType();
        if(type != null){
            if(containsRaw(type))
                type.setRaw(true);
        }
    }

    private boolean containsRaw(ProducedType type) {
        for(ProducedType typeArg : type.getTypeArguments().values()){
            TypeDeclaration typeDeclaration = typeArg.getDeclaration();
            if(typeDeclaration instanceof UnionType){
                UnionType ut = (UnionType) typeDeclaration;
                List<ProducedType> caseTypes = ut.getCaseTypes();
                // special case for optional types
                if(caseTypes.size() == 2
                        && (isNothing(caseTypes.get(0))
                                || isNothing(caseTypes.get(1))))
                    return false;
                return true;
            }
            if(typeDeclaration instanceof IntersectionType){
                IntersectionType ut = (IntersectionType) typeDeclaration;
                List<ProducedType> satisfiedTypes = ut.getSatisfiedTypes();
                // special case for non-optional types
                if(satisfiedTypes.size() == 2
                        && (isObject(satisfiedTypes.get(0))
                                || isObject(satisfiedTypes.get(1))))
                    return false;
                return true;
            }
            if(containsRaw(typeArg))
                return true;
        }
        return false;
    }

    private void boxMethod(Method method) {
        // deal with invalid input
        if(method == null)
            return;
        Declaration refined = CodegenUtil.getTopmostRefinedDeclaration(method);
        // deal with invalid input
        if(refined == null
                || (!(refined instanceof Method)))
            return;
        Method refinedMethod = (Method)refined;
        List<ParameterList> methodParameterLists = method.getParameterLists();
        List<ParameterList> refinedParameterLists = refinedMethod.getParameterLists();
        // A Callable, which never have primitive parameters
        setBoxingState(method, refinedMethod);
        
        // deal with invalid input
        if(methodParameterLists.isEmpty()
                || refinedParameterLists.isEmpty())
            return;

        boxParameterLists(methodParameterLists, refinedParameterLists);
    }
    
    private void boxParameterLists(List<ParameterList> paramLists, List<ParameterList> refinedParamLists) {
        if (paramLists.size() != refinedParamLists.size()) {
            throw new RuntimeException();
        }
        for (int ii = 0; ii < paramLists.size(); ii++) {
            ParameterList paramList = paramLists.get(ii);
            ParameterList refinedParamList = refinedParamLists.get(ii);
            if (paramList.getParameters().size() != 
                    refinedParamList.getParameters().size()) {
                throw new RuntimeException();
            }
            for (int jj = 0; jj < paramList.getParameters().size(); jj++) {
                Parameter param = paramList.getParameters().get(jj);
                Parameter refinedParam = refinedParamList.getParameters().get(jj);
                if (param instanceof Functional && refinedParam instanceof Functional) {
                    boxParameterLists(((Functional)param).getParameterLists(),
                            ((Functional)refinedParam).getParameterLists());
                }
                setBoxingState(param, refinedParam);
            }
        }
    }

    @Override
    public void visit(ClassDefinition that) {
        super.visit(that);
        Class klass = that.getDeclarationModel();
        // deal with invalid input
        if(klass == null)
            return;
        List<ParameterList> parameterLists = klass.getParameterLists();
        // deal with invalid input
        if(parameterLists.isEmpty())
            return;
        boxParameterLists(parameterLists, parameterLists);
    }
    
    private void setBoxingState(TypedDeclaration declaration, TypedDeclaration refinedDeclaration) {
        ProducedType type = declaration.getType();
        if(type == null){
            // an error must have already been reported
            return;
        }
        // inherit underlying type constraints
        if(refinedDeclaration != declaration && type.getUnderlyingType() == null)
            type.setUnderlyingType(refinedDeclaration.getType().getUnderlyingType());
        
        // abort if our boxing state has already been set
        if(declaration.getUnboxed() != null)
            return;
        
        // functional parameter return values are always boxed
        if(declaration instanceof FunctionalParameter){
            declaration.setUnboxed(false);
            return;
        }
        
        if(refinedDeclaration != declaration){
            // make sure refined declarations have already been set
            if(refinedDeclaration.getUnboxed() == null)
                setBoxingState(refinedDeclaration, refinedDeclaration);
            // inherit
            declaration.setUnboxed(refinedDeclaration.getUnboxed());
        }else if((isCeylonBasicType(type) || Decl.isUnboxedVoid(declaration))
           && !(refinedDeclaration.getTypeDeclaration() instanceof TypeParameter)
           && !(refinedDeclaration.getContainer() instanceof FunctionalParameter)
           && !(refinedDeclaration instanceof Functional && Decl.isMpl((Functional)refinedDeclaration))){
            declaration.setUnboxed(true);
        }else
            declaration.setUnboxed(false);
    }

    private void boxAttribute(TypedDeclaration declaration) {
        // deal with invalid input
        if(declaration == null)
            return;
        TypedDeclaration refinedDeclaration = (TypedDeclaration)CodegenUtil.getTopmostRefinedDeclaration(declaration);
        // deal with invalid input
        if(refinedDeclaration == null)
            return;
        setBoxingState(declaration, refinedDeclaration);
    }
    
    @Override
    public void visit(AnyAttribute that) {
        super.visit(that);
        TypedDeclaration declaration = that.getDeclarationModel();
        boxAttribute(declaration);
        rawTypedDeclaration(declaration);
    }

    @Override
    public void visit(AttributeArgument that) {
        super.visit(that);
        boxAttribute(that.getDeclarationModel());
    }

    @Override
    public void visit(AttributeSetterDefinition that) {
        super.visit(that);
        Setter declarationModel = that.getDeclarationModel();
        // deal with invalid input
        if(declarationModel == null)
            return;
        TypedDeclaration declaration = declarationModel.getParameter();
        boxAttribute(declaration);
    }

    @Override
    public void visit(Variable that) {
        super.visit(that);
        TypedDeclaration declaration = that.getDeclarationModel();
        // deal with invalid input
        if(declaration == null)
            return;
        setBoxingState(declaration, declaration);
    }
}
