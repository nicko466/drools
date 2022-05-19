/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler.builder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.kie.internal.ruleunit.RuleUnitDescription;
import org.kie.internal.ruleunit.RuleUnitVariable;

import static org.drools.modelcompiler.builder.JavaParserCompiler.getPrettyPrinter;

public class RuleUnitWriter {

    private final RuleUnitDescription ruleUnitDescr;
    private final PackageModel.RuleSourceResult ruleSourceResult;
    private final PackageModel pkgModel;

    private final String uuid = "" + Math.abs( UUID.randomUUID().getLeastSignificantBits() );

    public RuleUnitWriter(PackageModel pkgModel, PackageModel.RuleSourceResult ruleSourceResult, RuleUnitDescription ruleUnitDescr) {
        this.pkgModel = pkgModel;
        this.ruleSourceResult = ruleSourceResult;
        this.ruleUnitDescr = ruleUnitDescr;
    }

    public String getUnitName() {
        return pkgModel.getPathName() + "/" + getRuleUnitSimpleClassName() + ".java";
    }

    public String getInstanceName() {
        return pkgModel.getPathName() + "/" + getRuleUnitInstanceSimpleClassName() + ".java";
    }

    public String getRuleUnitClassName() {
        return pkgModel.getName() + "." + getRuleUnitSimpleClassName();
    }

    private String getRuleUnitSimpleClassName() {
        return ruleUnitDescr.getSimpleName() + "RuleUnit" + uuid;
    }

    private String getRuleUnitInstanceSimpleClassName() {
        return ruleUnitDescr.getSimpleName() + "RuleUnitInstance" + uuid;
    }

    public String getUnitSource() {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parseResource("RuleUnitTemplate.java");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ClassOrInterfaceDeclaration parsedClass = cu
                .getClassByName("CLASS_NAME")
                .orElseThrow(RuntimeException::new);

        cu.setPackageDeclaration(pkgModel.getName());

        parsedClass.setName(getRuleUnitSimpleClassName());
        parsedClass.findAll(ConstructorDeclaration.class)
                .forEach(c -> c.setName(getRuleUnitSimpleClassName()));
        parsedClass.findAll(ClassOrInterfaceType.class, c -> "CLASS_NAME".equals(c.asString()))
                .forEach(c -> c.setName(getRuleUnitSimpleClassName()));
        parsedClass.findAll(ClassOrInterfaceType.class, c -> "RULE_UNIT_CLASS".equals(c.asString()))
                .forEach(c -> c.setName(ruleUnitDescr.getRuleUnitName()));

        parsedClass.findAll(ClassOrInterfaceType.class, c -> "RULE_UNIT_INSTANCE_CLASS".equals(c.asString()))
                .forEach(c -> c.setName(getRuleUnitInstanceSimpleClassName()));
        parsedClass.findAll(ObjectCreationExpr.class, c -> "RULE_UNIT_INSTANCE_CLASS".equals(c.getTypeAsString()))
                .forEach(c -> c.setType(getRuleUnitInstanceSimpleClassName()));

        parsedClass.findAll(ObjectCreationExpr.class, c -> "RULE_UNIT_MODEL".equals(c.getTypeAsString()))
                .forEach(c -> c.setType(ruleSourceResult.getModelsByUnit().get(ruleUnitDescr.getRuleUnitName())));

        return getPrettyPrinter().print(cu);
    }

    public String getInstanceSource() {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parseResource("RuleUnitInstanceTemplate.java");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ClassOrInterfaceDeclaration parsedClass = cu
                        .getClassByName("CLASS_NAME")
                        .orElseThrow(RuntimeException::new);

        cu.setPackageDeclaration(pkgModel.getName());

        parsedClass.setName(getRuleUnitInstanceSimpleClassName());
        parsedClass.findAll(ConstructorDeclaration.class)
                .forEach(c -> c.setName(getRuleUnitInstanceSimpleClassName()));
        parsedClass.findAll(ClassOrInterfaceType.class, c -> "RULE_UNIT_CLASS".equals(c.asString()))
                .forEach(c -> c.setName(ruleUnitDescr.getRuleUnitName()));

        MethodDeclaration bindMethod = parsedClass.findAll(MethodDeclaration.class, c -> "bind".equals(c.getNameAsString())).get(0);
        BlockStmt methodBlock = new BlockStmt();
        bindMethod.setBody(methodBlock);

        for (RuleUnitVariable m : ruleUnitDescr.getUnitVarDeclarations()) {
            String methodName = m.getter();
            String propertyName = m.getName();

            if (m.isDataSource()) {

                //  ruleUnit.$method())
                Expression fieldAccessor =
                        new MethodCallExpr(new NameExpr("ruleUnit"), methodName);

                // .subscribe( new EntryPointDataProcessor(runtime.getEntryPoint()) )

                MethodCallExpr drainInto = new MethodCallExpr(fieldAccessor, "subscribe")
                        .addArgument(new ObjectCreationExpr(null, StaticJavaParser.parseClassOrInterfaceType("org.drools.ruleunits.impl.EntryPointDataProcessor"), NodeList.nodeList(
                                new MethodCallExpr(
                                        new NameExpr("evaluator"), "getEntryPoint",
                                        NodeList.nodeList(new StringLiteralExpr(propertyName))))));

                methodBlock.addStatement(drainInto);
            }

            MethodCallExpr setGlobalCall = new MethodCallExpr(new NameExpr("evaluator"), "setGlobal");
            setGlobalCall.addArgument(new StringLiteralExpr(propertyName));
            setGlobalCall.addArgument(new MethodCallExpr(new NameExpr("ruleUnit"), methodName));
            methodBlock.addStatement(setGlobalCall);
        }

        return getPrettyPrinter().print(cu);
    }
}