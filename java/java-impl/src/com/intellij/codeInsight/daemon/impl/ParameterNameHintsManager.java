/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.daemon.impl;

import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class ParameterNameHintsManager {
  private static final List<Couple<String>> COMMONLY_USED_PARAMETER_PAIR = ContainerUtil.newArrayList(
    Couple.of("begin", "end"),
    Couple.of("start", "end"),
    Couple.of("first", "last"),
    Couple.of("first", "second"),
    Couple.of("from", "to"),
    Couple.of("key", "value"),
    Couple.of("min", "max")
  );
  
  private static final List<String> COMMON_METHODS = ContainerUtil.newArrayList(
    "get", "set", "contains", "append", 
    "print", "println", 
    "charAt", "startsWith", "indexOf"
  );

  @NotNull
  private final List<InlayInfo> myDescriptors;

  public ParameterNameHintsManager(@NotNull PsiCallExpression callExpression) {
    PsiExpression[] callArguments = getArguments(callExpression);
    JavaResolveResult resolveResult = callExpression.resolveMethodGenerics();
    
    List<InlayInfo> descriptors = Collections.emptyList();
    if (resolveResult.getElement() instanceof PsiMethod
        && isMethodToShowParams(resolveResult)
        && hasUnclearExpressions(callArguments)) 
    {
      PsiMethod method = (PsiMethod)resolveResult.getElement();
      PsiParameter[] parameters = method.getParameterList().getParameters();
      descriptors = buildDescriptorsForLiteralArguments(callArguments, parameters, resolveResult);
    }

    myDescriptors = descriptors;
  }

  private static boolean isMethodToShowParams(JavaResolveResult resolveResult) {
    PsiElement element = resolveResult.getElement();
    if (element instanceof PsiMethod) {
      PsiMethod method = (PsiMethod)element;
      return !isSetter(method) && !isCommonMethod(method);
    }
    return false;
  }

  private static boolean isCommonMethod(PsiMethod method) {
    String methodName = method.getName();
    return COMMON_METHODS.stream().anyMatch((name) -> methodName.equals(name));
  }

  private static boolean isSetter(PsiMethod method) {
    String methodName = method.getName();
    if (method.getParameterList().getParametersCount() == 1
        && methodName.startsWith("set")
        && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3))) {
      return true;
    }
    return false;
  }

  static boolean isUnclearExpression(@Nullable PsiElement callArgument) {
    if (callArgument instanceof PsiLiteralExpression)
      return true;

    if (callArgument instanceof PsiPrefixExpression) {
      PsiPrefixExpression expr = (PsiPrefixExpression)callArgument;
      IElementType tokenType = expr.getOperationTokenType();
      return (JavaTokenType.MINUS.equals(tokenType)
              || JavaTokenType.PLUS.equals(tokenType)) && expr.getOperand() instanceof PsiLiteralExpression;
    }

    if (callArgument instanceof PsiThisExpression) {
      return true;
    }

    return false;
  }

  @NotNull
  private static PsiExpression[] getArguments(@NotNull PsiCallExpression call) {
    PsiExpressionList callArgumentsList = call.getArgumentList();
    return callArgumentsList == null ? PsiExpression.EMPTY_ARRAY : callArgumentsList.getExpressions();
  }

  @NotNull
  public List<InlayInfo> getDescriptors() {
    return myDescriptors;
  }

  @NotNull
  private static List<InlayInfo> buildDescriptorsForLiteralArguments(@NotNull PsiExpression[] callArguments,
                                                                     @NotNull PsiParameter[] parameters,
                                                                     @NotNull JavaResolveResult resolveResult) {
    if (callArguments.length == 2 && parameters.length == 2 
        && isCommonlyNamedParameterPair(0, 1, parameters)) 
    {
      return ContainerUtil.emptyList();
    }

    List<InlayInfo> descriptors = ContainerUtil.newArrayList();
    
    int index = 0;
    while (index < callArguments.length && index < parameters.length) {
      if (shouldInlineParameterName(index, callArguments, parameters, resolveResult)) {
        descriptors.add(createInlayInfo(callArguments[index], parameters[index]));
      }
      index++;
    }

    return descriptors;
  }

  @NotNull
  private static InlayInfo createInlayInfo(@NotNull PsiExpression callArgument, @NotNull PsiParameter methodParam) {
    String paramName = ((methodParam.getType() instanceof PsiEllipsisType) ? "..." : "") + methodParam.getName();
    return new InlayInfo(paramName, callArgument.getTextRange().getStartOffset());
  }

  private static boolean isCommonlyNamedParameterPair(int first, int second, PsiParameter[] parameters) {
    if (!(first < parameters.length && second < parameters.length)) return false;

    String firstParamName = parameters[first].getName();
    String secondParamName = parameters[second].getName();
    if (firstParamName == null || secondParamName == null) return false;

    for (Couple<String> knownPair : COMMONLY_USED_PARAMETER_PAIR) {
      if (StringUtil.containsIgnoreCase(firstParamName, knownPair.first)
          && StringUtil.containsIgnoreCase(secondParamName, knownPair.second)) {
        return true;
      }
    }

    return false;
  }

  private static boolean shouldInlineParameterName(int paramIndex,
                                                   @NotNull PsiExpression[] callArguments,
                                                   @NotNull PsiParameter[] parameters,
                                                   @NotNull JavaResolveResult resolveResult) {
    final PsiExpression argument = callArguments[paramIndex];
    if (argument.getType() == null) return false;
    
    final PsiParameter parameter = parameters[paramIndex];

    PsiType argType = argument.getType();
    PsiType paramType = parameter.getType();

    if (isVarargParam(paramType, argType) && hasUnclearExpression(paramIndex, callArguments)) {
      return true;
    }

    if (isUnclearExpression(argument)) {
      PsiType parameterType = resolveResult.getSubstitutor().substitute(paramType);
      return TypeConversionUtil.isAssignable(parameterType, argType);
    }

    return false;
  }
  
  private static boolean hasUnclearExpression(int index, PsiExpression[] callArguments) {
    for (int i = index; i < callArguments.length; i++) {
      PsiExpression arg = callArguments[i];
      if (isUnclearExpression(arg)) return true;
    }
    return false;
  }

  private static boolean isVarargParam(@NotNull PsiType param, @NotNull PsiType argument) {
    PsiType deepType = param.getDeepComponentType();
    return param instanceof PsiEllipsisType && TypeConversionUtil.isAssignable(deepType, argument);
  }

  private static boolean hasUnclearExpressions(@NotNull PsiExpression[] arguments) {
    for (PsiExpression argument : arguments) {
      if (isUnclearExpression(argument)) return true;
    }
    return false;
  }
}
