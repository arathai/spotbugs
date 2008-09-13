/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2007 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugAnnotation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.Detector;
import edu.umd.cs.findbugs.FieldAnnotation;
import edu.umd.cs.findbugs.FindBugsAnalysisFeatures;
import edu.umd.cs.findbugs.LocalVariableAnnotation;
import edu.umd.cs.findbugs.MethodAnnotation;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.UseAnnotationDatabase;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.BasicBlock;
import edu.umd.cs.findbugs.ba.CFG;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.DataflowValueChooser;
import edu.umd.cs.findbugs.ba.Edge;
import edu.umd.cs.findbugs.ba.Hierarchy;
import edu.umd.cs.findbugs.ba.INullnessAnnotationDatabase;
import edu.umd.cs.findbugs.ba.JavaClassAndMethod;
import edu.umd.cs.findbugs.ba.Location;
import edu.umd.cs.findbugs.ba.MissingClassException;
import edu.umd.cs.findbugs.ba.NullnessAnnotation;
import edu.umd.cs.findbugs.ba.SignatureConverter;
import edu.umd.cs.findbugs.ba.SignatureParser;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XField;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.XMethodParameter;
import edu.umd.cs.findbugs.ba.interproc.PropertyDatabase;
import edu.umd.cs.findbugs.ba.npe.IsNullValue;
import edu.umd.cs.findbugs.ba.npe.IsNullValueDataflow;
import edu.umd.cs.findbugs.ba.npe.IsNullValueFrame;
import edu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonCollector;
import edu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonFinder;
import edu.umd.cs.findbugs.ba.npe.NullValueUnconditionalDeref;
import edu.umd.cs.findbugs.ba.npe.ParameterNullnessProperty;
import edu.umd.cs.findbugs.ba.npe.ParameterNullnessPropertyDatabase;
import edu.umd.cs.findbugs.ba.npe.PointerUsageRequiringNonNullValue;
import edu.umd.cs.findbugs.ba.npe.RedundantBranch;
import edu.umd.cs.findbugs.ba.npe.ReturnPathType;
import edu.umd.cs.findbugs.ba.npe.ReturnPathTypeDataflow;
import edu.umd.cs.findbugs.ba.npe.UsagesRequiringNonNullValues;
import edu.umd.cs.findbugs.ba.type.TypeDataflow;
import edu.umd.cs.findbugs.ba.type.TypeFrame;
import edu.umd.cs.findbugs.ba.vna.ValueNumber;
import edu.umd.cs.findbugs.ba.vna.ValueNumberDataflow;
import edu.umd.cs.findbugs.ba.vna.ValueNumberFrame;
import edu.umd.cs.findbugs.ba.vna.ValueNumberSourceInfo;
import edu.umd.cs.findbugs.classfile.CheckedAnalysisException;
import edu.umd.cs.findbugs.log.Profiler;
import edu.umd.cs.findbugs.props.GeneralWarningProperty;
import edu.umd.cs.findbugs.props.WarningProperty;
import edu.umd.cs.findbugs.props.WarningPropertySet;
import edu.umd.cs.findbugs.props.WarningPropertyUtil;
import edu.umd.cs.findbugs.visitclass.Util;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LineNumberTable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ATHROW;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.IFNONNULL;
import org.apache.bcel.generic.IFNULL;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionTargeter;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.PUTFIELD;
import org.apache.bcel.generic.ReturnInstruction;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.CheckForNull;

/**
 * A Detector to find instructions where a NullPointerException might be raised.
 * We also look for useless reference comparisons involving null and non-null
 * values.
 * 
 * @author David Hovemeyer
 * @author William Pugh
 * @see edu.umd.cs.findbugs.ba.npe.IsNullValueAnalysis
 */
public class FindNullDeref implements Detector, UseAnnotationDatabase,
		NullDerefAndRedundantComparisonCollector {


    public static final boolean DEBUG = SystemProperties
			.getBoolean("fnd.debug");

	private static final boolean DEBUG_NULLARG = SystemProperties
			.getBoolean("fnd.debug.nullarg");

	private static final boolean DEBUG_NULLRETURN = SystemProperties
			.getBoolean("fnd.debug.nullreturn");

	private static final boolean MARK_DOOMED = SystemProperties
			.getBoolean("fnd.markdoomed", true);

	private static final boolean REPORT_SAFE_METHOD_TARGETS = true;

	private static final String METHOD = SystemProperties
			.getProperty("fnd.method");

	private static final String CLASS = SystemProperties
			.getProperty("fnd.class");

	// Fields
	private final BugReporter bugReporter;
	private final BugAccumulator bugAccumulator;

	// Cached database stuff
	private ParameterNullnessPropertyDatabase unconditionalDerefParamDatabase;

	private boolean checkedDatabases = false;

	// Transient state
	private ClassContext classContext;

	private Method method;

	private IsNullValueDataflow invDataflow;
	
	private ValueNumberDataflow vnaDataflow;

	private BitSet previouslyDeadBlocks;

	private NullnessAnnotation methodAnnotation;

	public FindNullDeref(BugReporter bugReporter) {
		this.bugReporter = bugReporter;
		this.bugAccumulator = new BugAccumulator(bugReporter);
	}

	public void visitClassContext(ClassContext classContext) {
		this.classContext = classContext;

		String currentMethod = null;

		JavaClass jclass = classContext.getJavaClass();
		String className = jclass.getClassName();
		if (CLASS != null && !className.equals(CLASS))
			return;
		Method[] methodList = jclass.getMethods();
		for (Method method : methodList) {
			try {
				if (method.isAbstract() || method.isNative()
						|| method.getCode() == null)
					continue;

				currentMethod = SignatureConverter.convertMethodSignature(jclass, method);

				if (METHOD != null && !method.getName().equals(METHOD))
					continue;
				if (DEBUG || DEBUG_NULLARG)
					System.out.println("Checking for NP in " + currentMethod);
				analyzeMethod(classContext, method);
			} catch (MissingClassException e) {
				bugReporter.reportMissingClass(e.getClassNotFoundException());
			} catch (DataflowAnalysisException e) {
				bugReporter.logError("While analyzing " + currentMethod
						+ ": FindNullDeref caught dae exception", e);
			} catch (CFGBuilderException e) {
				bugReporter.logError("While analyzing " + currentMethod
						+ ": FindNullDeref caught cfgb exception", e);
			}
			bugAccumulator.reportAccumulatedBugs();
		}
	}

	private void analyzeMethod(ClassContext classContext, Method method) throws DataflowAnalysisException, CFGBuilderException

			{
		if (DEBUG || DEBUG_NULLARG)
			System.out.println("Pre FND ");

		MethodGen methodGen = classContext.getMethodGen(method);
		if (methodGen == null)
			return;
		if (!checkedDatabases) {
			checkDatabases();
			checkedDatabases = true;
		}

		// UsagesRequiringNonNullValues uses =
		// classContext.getUsagesRequiringNonNullValues(method);
		this.method = method;
		this.methodAnnotation = getMethodNullnessAnnotation();

		if (DEBUG || DEBUG_NULLARG)
			System.out.println("FND: "
					+ SignatureConverter.convertMethodSignature(methodGen));



		this.previouslyDeadBlocks = findPreviouslyDeadBlocks();

		// Get the IsNullValueDataflow for the method from the ClassContext
		invDataflow = classContext.getIsNullValueDataflow(method);
		
		vnaDataflow = classContext.getValueNumberDataflow(method);
		
		// Create a NullDerefAndRedundantComparisonFinder object to do the
		// actual
		// work. It will call back to report null derefs and redundant null
		// comparisons
		// through the NullDerefAndRedundantComparisonCollector interface we
		// implement.
		NullDerefAndRedundantComparisonFinder worker = new NullDerefAndRedundantComparisonFinder(
				classContext, method, this);
		worker.execute();

		checkCallSitesAndReturnInstructions();

	}

	/**
	 * Find set of blocks which were known to be dead before doing the null
	 * pointer analysis.
	 * 
	 * @return set of previously dead blocks, indexed by block id
	 * @throws CFGBuilderException
	 * @throws DataflowAnalysisException
	 */
	private BitSet findPreviouslyDeadBlocks() throws DataflowAnalysisException,
			CFGBuilderException {
		BitSet deadBlocks = new BitSet();
		ValueNumberDataflow vnaDataflow = classContext
				.getValueNumberDataflow(method);
		for (Iterator<BasicBlock> i = vnaDataflow.getCFG().blockIterator(); i
				.hasNext();) {
			BasicBlock block = i.next();
			ValueNumberFrame vnaFrame = vnaDataflow.getStartFact(block);
			if (vnaFrame.isTop()) {
				deadBlocks.set(block.getLabel());
			}
		}

		return deadBlocks;
	}

	/**
	 * Check whether or not the various interprocedural databases we can use
	 * exist and are nonempty.
	 */
	private void checkDatabases() {
		AnalysisContext analysisContext = AnalysisContext
				.currentAnalysisContext();
		unconditionalDerefParamDatabase = analysisContext
				.getUnconditionalDerefParamDatabase();
	}

	private <DatabaseType extends PropertyDatabase<?, ?>> boolean isDatabaseNonEmpty(
			DatabaseType database) {
		return database != null && !database.isEmpty();
	}

	/**
	 * See if the currently-visited method declares a
	 * 
	 * @NonNull annotation, or overrides a method which declares a
	 * @NonNull annotation.
	 */
	private NullnessAnnotation getMethodNullnessAnnotation() {

		if (method.getSignature().indexOf(")L") >= 0
				|| method.getSignature().indexOf(")[") >= 0) {
			if (DEBUG_NULLRETURN) {
				System.out.println("Checking return annotation for "
						+ SignatureConverter.convertMethodSignature(
								classContext.getJavaClass(), method));
			}

			XMethod m = XFactory.createXMethod(classContext.getJavaClass(),
					method);
			return AnalysisContext.currentAnalysisContext()
					.getNullnessAnnotationDatabase().getResolvedAnnotation(m,
							false);
		}
		return NullnessAnnotation.UNKNOWN_NULLNESS;
	}

	static class CheckCallSitesAndReturnInstructions {}

	private void checkCallSitesAndReturnInstructions() {
		Profiler profiler = Profiler.getInstance();
		profiler.start(CheckCallSitesAndReturnInstructions.class);
		try {
		ConstantPoolGen cpg = classContext.getConstantPoolGen();
		TypeDataflow typeDataflow = classContext.getTypeDataflow(method);

		for (Iterator<Location> i = classContext.getCFG(method)
				.locationIterator(); i.hasNext();) {
			Location location = i.next();
			Instruction ins = location.getHandle().getInstruction();
			try {
				ValueNumberFrame  vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
				if (!vnaFrame.isValid()) continue;

				if (ins instanceof InvokeInstruction) {
					examineCallSite(location, cpg, typeDataflow);
				} else if (methodAnnotation == NullnessAnnotation.NONNULL
						&& ins.getOpcode() == Constants.ARETURN) {

					examineReturnInstruction(location);
				} else if (ins instanceof PUTFIELD) {
					examinePutfieldInstruction(location, (PUTFIELD) ins, cpg);
				}
			} catch (ClassNotFoundException e) {
				bugReporter.reportMissingClass(e);
			}
		}
		} catch (CheckedAnalysisException e) {
		   AnalysisContext.logError("error:", e);
		} finally {
			profiler.end(CheckCallSitesAndReturnInstructions.class);
		}
	}

	private void examineCallSite(Location location, ConstantPoolGen cpg,
			TypeDataflow typeDataflow) throws DataflowAnalysisException,
			CFGBuilderException, ClassNotFoundException {

		InvokeInstruction invokeInstruction = (InvokeInstruction) location
				.getHandle().getInstruction();

		String methodName = invokeInstruction.getName(cpg);
		String signature = invokeInstruction.getSignature(cpg);

		// Don't check equals() calls.
		// If an equals() call unconditionally dereferences the parameter,
		// it is the fault of the method, not the caller.
		if (methodName.equals("equals")
				&& signature.equals("(Ljava/lang/Object;)Z"))
			return;

		int returnTypeStart = signature.indexOf(')');
		if (returnTypeStart < 0)
			return;
		String paramList = signature.substring(0, returnTypeStart + 1);

		if (paramList.equals("()")
				|| (paramList.indexOf("L") < 0 && paramList.indexOf('[') < 0))
			// Method takes no arguments, or takes no reference arguments
			return;

		// See if any null arguments are passed
		IsNullValueFrame frame = classContext.getIsNullValueDataflow(method)
				.getFactAtLocation(location);
		if (!frame.isValid())
			return;
		BitSet nullArgSet = frame.getArgumentSet(invokeInstruction, cpg,
				new DataflowValueChooser<IsNullValue>() {
					public boolean choose(IsNullValue value) {
						// Only choose non-exception values.
						// Values null on an exception path might be due to
						// infeasible control flow.
						return value.mightBeNull() && !value.isException()
								&& !value.isReturnValue();
					}
				});
		BitSet definitelyNullArgSet = frame.getArgumentSet(invokeInstruction,
				cpg, new DataflowValueChooser<IsNullValue>() {
					public boolean choose(IsNullValue value) {
						return value.isDefinitelyNull();
					}
				});
		nullArgSet.and(definitelyNullArgSet);
		if (nullArgSet.isEmpty())
			return;
		if (DEBUG_NULLARG) {
			System.out.println("Null arguments passed: " + nullArgSet);
			System.out.println("Frame is: " + frame);
			System.out.println("# arguments: "
					+ frame.getNumArguments(invokeInstruction, cpg));
			XMethod xm = XFactory.createXMethod(invokeInstruction, cpg);
			System.out.print("Signature: " + xm.getSignature());
		}

		if (unconditionalDerefParamDatabase != null) {
			checkUnconditionallyDereferencedParam(location, cpg, typeDataflow,
					invokeInstruction, nullArgSet, definitelyNullArgSet);
		}

		if (DEBUG_NULLARG) {
			System.out.println("Checking nonnull params");
		}
		checkNonNullParam(location, cpg, typeDataflow, invokeInstruction,
				nullArgSet, definitelyNullArgSet);

	}

	private void examinePutfieldInstruction(Location location, PUTFIELD ins,
			ConstantPoolGen cpg) throws DataflowAnalysisException,
			CFGBuilderException {

		IsNullValueDataflow invDataflow = classContext
				.getIsNullValueDataflow(method);
		IsNullValueFrame frame = invDataflow.getFactAtLocation(location);
		if (!frame.isValid())
			return;
		IsNullValue tos = frame.getTopValue();
		if (tos.isDefinitelyNull()) {
			XField field = XFactory.createXField(ins, cpg);
			NullnessAnnotation annotation = AnalysisContext
					.currentAnalysisContext().getNullnessAnnotationDatabase()
					.getResolvedAnnotation(field, false);
			if (annotation == NullnessAnnotation.NONNULL) {

				BugAnnotation variableAnnotation = null;
				try {
				   ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
				   ValueNumber valueNumber = vnaFrame.getTopValue();
				   variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method,
						  location, valueNumber, vnaFrame);

			   } catch (DataflowAnalysisException e) {
				 AnalysisContext.logError("error", e);
			   } catch (CFGBuilderException e) {
				   AnalysisContext.logError("error", e);
			   }

				BugInstance warning = new BugInstance(this,
						"NP_STORE_INTO_NONNULL_FIELD",
						tos.isDefinitelyNull() ? HIGH_PRIORITY
								: NORMAL_PRIORITY).addClassAndMethod(classContext.getJavaClass(),method)
						.addField(field).addOptionalAnnotation(variableAnnotation).addSourceLine(classContext,
						method, location);

				bugReporter.reportBug(warning);
			}
		}
	}

	private void examineReturnInstruction(Location location)
			throws DataflowAnalysisException, CFGBuilderException {
		if (DEBUG_NULLRETURN) {
			System.out.println("Checking null return at " + location);
		}

		IsNullValueDataflow invDataflow = classContext
				.getIsNullValueDataflow(method);
		IsNullValueFrame frame = invDataflow.getFactAtLocation(location);
		ValueNumberFrame  vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
		if (!vnaFrame.isValid()) return;
		ValueNumber valueNumber = vnaFrame.getTopValue();
		if (!frame.isValid())
			return;
		IsNullValue tos = frame.getTopValue();
		if (tos.isDefinitelyNull()) {
			BugAnnotation variable = ValueNumberSourceInfo.findAnnotationFromValueNumber(method,
					location, valueNumber, vnaFrame);

			String bugPattern = "NP_NONNULL_RETURN_VIOLATION";
			int priority = NORMAL_PRIORITY;
			if (tos.isDefinitelyNull() && !tos.isException())
				priority = HIGH_PRIORITY;
			String methodName = method.getName();
			if (methodName.equals("clone")) {
				bugPattern = "NP_CLONE_COULD_RETURN_NULL";
				priority = NORMAL_PRIORITY;
			} else if (methodName.equals("toString")) {
				bugPattern = "NP_TOSTRING_COULD_RETURN_NULL";
				priority = NORMAL_PRIORITY;
			}
			BugInstance warning = new BugInstance(this, bugPattern, priority)
					.addClassAndMethod(classContext.getJavaClass(), method).addOptionalAnnotation(variable);
			bugAccumulator.accumulateBug(warning, SourceLineAnnotation.fromVisitedInstruction(classContext, method,
							location));
		}
	}
	private boolean hasManyPreceedingNullTests(int pc) {
		int ifNullTests = 0;
		int ifNonnullTests = 0;
		BitSet seen = new BitSet();
		try {
	        for (Iterator<Location> i = classContext.getCFG(method)
	        		.locationIterator(); i.hasNext();) {
	        	Location loc = i.next();
	        	int pc2 = loc.getHandle().getPosition();
	        	if (pc2 >= pc || pc2 < pc-30) continue;
	        	Instruction ins = loc.getHandle().getInstruction();
	        	if (ins instanceof IFNONNULL && !seen.get(pc2)) {
	        		ifNonnullTests++;
	        		seen.set(pc2);
	        	}
	        	else if (ins instanceof IFNULL && !seen.get(pc2)) {
	        		ifNullTests++;
	        		seen.set(pc2);
	        	}
	        }
	        boolean result = ifNullTests + ifNonnullTests > 2;
			
	        // System.out.println("Preceeding null tests " + ifNullTests + " " + ifNonnullTests + " " + result);
			return result;
        } catch (CFGBuilderException e) {
	        return false;
        }
	}
	private boolean safeCallToPrimateParseMethod(XMethod calledMethod, Location location) {
		if (calledMethod.getClassName().equals("java.lang.Integer")) {
			int position = location.getHandle().getPosition();
			ConstantPool constantPool = classContext.getJavaClass().getConstantPool();
			Code code = method.getCode();
			int catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code,
					"java/lang/NumberFormatException", position);
			if (catchSize < Integer.MAX_VALUE)
				return true;
			catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code,
					"java/lang/IllegalArgumentException", position);
			if (catchSize < Integer.MAX_VALUE)
				return true;
			catchSize = Util.getSizeOfSurroundingTryBlock(constantPool, code,
					"java/lang/RuntimeException", position);
			if (catchSize < Integer.MAX_VALUE)
				return true;
		}
		return false;
	}
	private void checkUnconditionallyDereferencedParam(Location location,
			ConstantPoolGen cpg, TypeDataflow typeDataflow,
			InvokeInstruction invokeInstruction, BitSet nullArgSet,
			BitSet definitelyNullArgSet) throws DataflowAnalysisException,
			ClassNotFoundException {

		boolean caught = inCatchNullBlock(location);
		if (caught && skipIfInsideCatchNull())
			return;

		// See what methods might be called here
		TypeFrame typeFrame = typeDataflow.getFactAtLocation(location);
		Set<JavaClassAndMethod> targetMethodSet = Hierarchy
				.resolveMethodCallTargets(invokeInstruction, typeFrame, cpg);
		if (DEBUG_NULLARG) {
			System.out.println("Possibly called methods: " + targetMethodSet);
		}

		// See if any call targets unconditionally dereference one of the null
		// arguments
		BitSet unconditionallyDereferencedNullArgSet = new BitSet();
		List<JavaClassAndMethod> dangerousCallTargetList = new LinkedList<JavaClassAndMethod>();
		List<JavaClassAndMethod> veryDangerousCallTargetList = new LinkedList<JavaClassAndMethod>();
		for (JavaClassAndMethod targetMethod : targetMethodSet) {
			if (DEBUG_NULLARG) {
				System.out.println("For target method " + targetMethod);
			}

			ParameterNullnessProperty property = unconditionalDerefParamDatabase
					.getProperty(targetMethod.toMethodDescriptor());
			if (property == null)
				continue;
			if (DEBUG_NULLARG) {
				System.out.println("\tUnconditionally dereferenced params: "
						+ property);
			}

			BitSet targetUnconditionallyDereferencedNullArgSet = property
					.getViolatedParamSet(nullArgSet);

			if (targetUnconditionallyDereferencedNullArgSet.isEmpty())
				continue;

			dangerousCallTargetList.add(targetMethod);

			unconditionallyDereferencedNullArgSet
					.or(targetUnconditionallyDereferencedNullArgSet);

			if (!property.getViolatedParamSet(definitelyNullArgSet).isEmpty())
				veryDangerousCallTargetList.add(targetMethod);
		}

		if (dangerousCallTargetList.isEmpty())
			return;

		WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<WarningProperty>();

		// See if there are any safe targets
		Set<JavaClassAndMethod> safeCallTargetSet = new HashSet<JavaClassAndMethod>();
		safeCallTargetSet.addAll(targetMethodSet);
		safeCallTargetSet.removeAll(dangerousCallTargetList);
		if (safeCallTargetSet.isEmpty()) {
			propertySet
					.addProperty(NullArgumentWarningProperty.ALL_DANGEROUS_TARGETS);
			if (dangerousCallTargetList.size() == 1) {
				propertySet
						.addProperty(NullArgumentWarningProperty.MONOMORPHIC_CALL_SITE);
			}
		}

		// Call to private method? In theory there should be only one possible
		// target.
		boolean privateCall = safeCallTargetSet.isEmpty()
				&& dangerousCallTargetList.size() == 1
				&& dangerousCallTargetList.get(0).getMethod().isPrivate();


		String bugType;
		int priority;
		if (privateCall
				|| invokeInstruction.getOpcode() == Constants.INVOKESTATIC
				|| invokeInstruction.getOpcode() == Constants.INVOKESPECIAL) {
			bugType = "NP_NULL_PARAM_DEREF_NONVIRTUAL";
			priority = HIGH_PRIORITY;
		} else if (safeCallTargetSet.isEmpty()) {
			bugType = "NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS";
			priority = NORMAL_PRIORITY;
		} else {
		  return;
		}

		if (caught)
			priority++;
		if (dangerousCallTargetList.size() > veryDangerousCallTargetList.size())
			priority++;
		else
			propertySet
					.addProperty(NullArgumentWarningProperty.ACTUAL_PARAMETER_GUARANTEED_NULL);
		XMethod calledFrom = XFactory.createXMethod(classContext.getJavaClass(), method);
		
		XMethod calledMethod = XFactory.createXMethod(invokeInstruction, cpg);
		if (safeCallToPrimateParseMethod(calledMethod, location)) return;
		BugInstance warning = new BugInstance(this,bugType, priority)
				.addClassAndMethod(classContext.getJavaClass(), method).addMethod(
						calledMethod)
				.describe("METHOD_CALLED").addSourceLine(classContext,
						method,  location);

		boolean uncallable = false;
		if (!AnalysisContext.currentXFactory().isCalledDirectlyOrIndirectly(calledFrom) 
				&& calledFrom.isPrivate()) {
			
			propertySet
			.addProperty(GeneralWarningProperty.IN_UNCALLABLE_METHOD);
			uncallable = true;
		}
		// Check which params might be null
		addParamAnnotations(location,
				definitelyNullArgSet, unconditionallyDereferencedNullArgSet, propertySet, warning);

		if (bugType.equals("NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS")) {
			// Add annotations for dangerous method call targets
			for (JavaClassAndMethod dangerousCallTarget : veryDangerousCallTargetList) {
				warning.addMethod(dangerousCallTarget).describe(
				MethodAnnotation.METHOD_DANGEROUS_TARGET_ACTUAL_GUARANTEED_NULL);
			}
			dangerousCallTargetList.removeAll(veryDangerousCallTargetList);
			if (DEBUG_NULLARG) {
				// Add annotations for dangerous method call targets
				for (JavaClassAndMethod dangerousCallTarget : dangerousCallTargetList) {
					warning.addMethod(dangerousCallTarget).describe(
					MethodAnnotation.METHOD_DANGEROUS_TARGET);
				}

				// Add safe method call targets.
				// This is useful to see which other call targets the analysis
				// considered.
				for (JavaClassAndMethod safeMethod : safeCallTargetSet) {
					warning.addMethod(safeMethod).describe(MethodAnnotation.METHOD_SAFE_TARGET);
				}
			}}

		decorateWarning(location, propertySet, warning);
		bugReporter.reportBug(warning);
	}

	private void decorateWarning(Location location,
			WarningPropertySet<WarningProperty> propertySet, BugInstance warning) {
		if (FindBugsAnalysisFeatures.isRelaxedMode()) {
			WarningPropertyUtil.addPropertiesForLocation(propertySet,
					classContext, method, location);
		}
		propertySet.decorateBugInstance(warning);
	}

	private void addParamAnnotations(Location location,
			BitSet definitelyNullArgSet, BitSet violatedParamSet,
			WarningPropertySet<? super NullArgumentWarningProperty> propertySet, BugInstance warning)   {
		ValueNumberFrame vnaFrame = null;
		try {
			vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
		} catch (DataflowAnalysisException e) {
			AnalysisContext.logError("error", e);
		} catch (CFGBuilderException e) {
			AnalysisContext.logError("error",  e);
		}

		InvokeInstruction instruction = (InvokeInstruction) location.getHandle().getInstruction();
		SignatureParser sigParser = new SignatureParser(instruction.getSignature(classContext.getConstantPoolGen()));


		for (int i = violatedParamSet.nextSetBit(0); i >= 0; i = violatedParamSet.nextSetBit(i + 1)) {
				boolean definitelyNull = definitelyNullArgSet.get(i);

				if (definitelyNull) 
					propertySet
							.addProperty(NullArgumentWarningProperty.ARG_DEFINITELY_NULL);
				ValueNumber valueNumber = null;
				if (vnaFrame != null) 
					try {
					valueNumber = vnaFrame. getArgument(instruction, classContext.getConstantPoolGen(), i, sigParser );
					BugAnnotation variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method,
							location, valueNumber, vnaFrame);
					warning.addOptionalAnnotation(variableAnnotation);
				} catch (DataflowAnalysisException e) {
					AnalysisContext.logError("error", e);
				}


				// Note: we report params as being indexed starting from 1, not
				// 0
				warning.addInt(i + 1).describe(
						definitelyNull ? "INT_NULL_ARG" : "INT_MAYBE_NULL_ARG");

		}
	}

	/**
	 * We have a method invocation in which a possibly or definitely null
	 * parameter is passed. Check it against the library of nonnull annotations.
	 * 
	 * @param location
	 * @param cpg
	 * @param typeDataflow
	 * @param invokeInstruction
	 * @param nullArgSet
	 * @param definitelyNullArgSet
	 * @throws ClassNotFoundException
	 */
	private void checkNonNullParam(Location location, ConstantPoolGen cpg,
			TypeDataflow typeDataflow, InvokeInstruction invokeInstruction,
			BitSet nullArgSet, BitSet definitelyNullArgSet)
			throws ClassNotFoundException {

		boolean caught = inCatchNullBlock(location);
		if (caught && skipIfInsideCatchNull())
			return;

		XMethod m = XFactory.createXMethod(invokeInstruction, cpg);

		INullnessAnnotationDatabase db = AnalysisContext
				.currentAnalysisContext().getNullnessAnnotationDatabase();
		SignatureParser sigParser = new SignatureParser(invokeInstruction.getSignature(cpg));
	   for (int i = nullArgSet.nextSetBit(0); i >= 0; i = nullArgSet
				.nextSetBit(i + 1)) {

			if (db.parameterMustBeNonNull(m, i)) {
				boolean definitelyNull = definitelyNullArgSet.get(i);
				if (DEBUG_NULLARG) {
					System.out.println("QQQ2: " + i + " -- " + i + " is null");
					System.out.println("QQQ nullArgSet: " + nullArgSet);
					System.out.println("QQQ dnullArgSet: "
							+ definitelyNullArgSet);
				}
				BugAnnotation variableAnnotation = null;
				 try {
					ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(method).getFactAtLocation(location);
					ValueNumber valueNumber = vnaFrame. getArgument(invokeInstruction, cpg, i, sigParser );
					variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method,
						   location, valueNumber, vnaFrame);

				} catch (DataflowAnalysisException e) {
				  AnalysisContext.logError("error", e);
				} catch (CFGBuilderException e) {
					AnalysisContext.logError("error", e);
				}

				int priority = definitelyNull ? HIGH_PRIORITY : NORMAL_PRIORITY;
				if (caught)
					priority++;
				String description = definitelyNull ? "INT_NULL_ARG" : "INT_MAYBE_NULL_ARG";
				BugInstance warning = new BugInstance(this,
						"NP_NONNULL_PARAM_VIOLATION", priority)
						.addClassAndMethod(classContext.getJavaClass(), method).addMethod(m)
						.describe("METHOD_CALLED").addInt(i+1).describe(
								description).addOptionalAnnotation(variableAnnotation).addSourceLine(
								classContext, method,
								location);

				bugReporter.reportBug(warning);
			}
		}

	}

	public void report() {
	}

	public boolean skipIfInsideCatchNull() {
		return classContext.getJavaClass().getClassName().indexOf("Test") >= 0
				|| method.getName().indexOf("test") >= 0
				|| method.getName().indexOf("Test") >= 0;
	}

	public void foundNullDeref(ClassContext classContext, Location location,
			ValueNumber valueNumber, IsNullValue refValue,
			ValueNumberFrame vnaFrame) {
		WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<WarningProperty>();
		if (valueNumber.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT))
			return;

		boolean onExceptionPath = refValue.isException();
		if (onExceptionPath) {
			propertySet.addProperty(GeneralWarningProperty.ON_EXCEPTION_PATH);
		}
		int pc = location.getHandle().getPosition();
		BugAnnotation variable = ValueNumberSourceInfo.findAnnotationFromValueNumber(method,
				location, valueNumber, vnaFrame);

		boolean duplicated = false;
		try {
			CFG cfg = classContext.getCFG(method);
			duplicated = cfg.getLocationsContainingInstructionWithOffset(pc)
					.size() > 1;
		} catch (CFGBuilderException e) {
		}

		boolean caught = inCatchNullBlock(location);
		if (caught && skipIfInsideCatchNull())
			return;

		if (!duplicated && refValue.isDefinitelyNull()) {
			String type = onExceptionPath ? "NP_ALWAYS_NULL_EXCEPTION"
					: "NP_ALWAYS_NULL";
			int priority = onExceptionPath ? NORMAL_PRIORITY : HIGH_PRIORITY;
			if (caught)
				priority++;
			reportNullDeref(propertySet, classContext, method, location, type,
					priority, variable);
		} else if (refValue.mightBeNull() && refValue.isParamValue()) {

			String type;
			int priority = NORMAL_PRIORITY;
			if (caught)
				priority++;

			if (method.getName().equals("equals")
					&& method.getSignature()
					.equals("(Ljava/lang/Object;)Z")) {
				if (caught)
					return;
				type = "NP_EQUALS_SHOULD_HANDLE_NULL_ARGUMENT";

			} else
				type = "NP_ARGUMENT_MIGHT_BE_NULL";


			if (DEBUG)
				System.out.println("Reporting null on some path: value="
						+ refValue);

			reportNullDeref(propertySet, classContext, method, location, type,
					priority, variable);
		}
	}

	private void reportNullDeref(WarningPropertySet<WarningProperty> propertySet,
			ClassContext classContext, Method method, Location location,
			String type, int priority, BugAnnotation variable) {

		BugInstance bugInstance = new BugInstance(this, type, priority)
				.addClassAndMethod(classContext.getJavaClass(), method);
		if (variable != null)
			bugInstance.add(variable);
		else
			bugInstance.add(new LocalVariableAnnotation("?", -1, -1));
		bugInstance.addSourceLine(classContext, method, 
				location).describe("SOURCE_LINE_DEREF");

		if (FindBugsAnalysisFeatures.isRelaxedMode()) {
			WarningPropertyUtil.addPropertiesForLocation(propertySet,
					classContext, method, location);
		}
		if (isDoomed(location)) {
			// Add a WarningProperty
			propertySet.addProperty(DoomedCodeWarningProperty.DOOMED_CODE);
		}
		propertySet.decorateBugInstance(bugInstance);

		bugReporter.reportBug(bugInstance);
	}

	public static boolean isThrower(BasicBlock target) {
		InstructionHandle ins = target.getFirstInstruction();
		int maxCount = 7;
		while (ins != null) {
			if (maxCount-- <= 0)
				break;
			Instruction i = ins.getInstruction();
			if (i instanceof ATHROW) {
				return true;
			}
			if (i instanceof InstructionTargeter
					|| i instanceof ReturnInstruction)
				return false;
			ins = ins.getNext();
		}
		return false;
	}

	public void foundRedundantNullCheck(Location location,
			RedundantBranch redundantBranch) {

		boolean isChecked = redundantBranch.firstValue.isChecked();
		boolean wouldHaveBeenAKaboom = redundantBranch.firstValue
				.wouldHaveBeenAKaboom();
		Location locationOfKaBoom = redundantBranch.firstValue
				.getLocationOfKaBoom();

		boolean createdDeadCode = false;
		boolean infeasibleEdgeSimplyThrowsException = false;
		Edge infeasibleEdge = redundantBranch.infeasibleEdge;
		if (infeasibleEdge != null) {
			if (DEBUG)
				System.out.println("Check if " + redundantBranch
						+ " creates dead code");
			BasicBlock target = infeasibleEdge.getTarget();

			if (DEBUG)
				System.out.println("Target block is  "
						+ (target.isExceptionThrower() ? " exception thrower"
								: " not exception thrower"));
			// If the block is empty, it probably doesn't matter that it was
			// killed.
			// FIXME: really, we should crawl the immediately reachable blocks
			// starting at the target block to see if any of them are dead and
			// nonempty.
			boolean empty = !target.isExceptionThrower()
					&& (target.isEmpty() || isGoto(target.getFirstInstruction()
							.getInstruction()));
			if (!empty) {
				try {
					if (classContext.getCFG(method).getNumIncomingEdges(target) > 1) {
						if (DEBUG)
							System.out
									.println("Target of infeasible edge has multiple incoming edges");
						empty = true;
					}
				} catch (CFGBuilderException e) {
					assert true; // ignore it
				}
			}
			if (DEBUG)
				System.out.println("Target block is  "
						+ (empty ? "empty" : "not empty"));

			if (!empty) {
				if (isThrower(target))
					infeasibleEdgeSimplyThrowsException = true;

			}
			if (!empty && !previouslyDeadBlocks.get(target.getLabel())) {
				if (DEBUG)
					System.out.println("target was alive previously");
				// Block was not dead before the null pointer analysis.
				// See if it is dead now by inspecting the null value frame.
				// If it's TOP, then the block became dead.
				IsNullValueFrame invFrame = invDataflow.getStartFact(target);
				createdDeadCode = invFrame.isTop();
				if (DEBUG)
					System.out.println("target is now "
							+ (createdDeadCode ? "dead" : "alive"));

			}
		}

		int priority;
		boolean valueIsNull = true;
		String warning;
		if (redundantBranch.secondValue == null) {
			if (redundantBranch.firstValue.isDefinitelyNull()) {
				warning = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE";
				priority = NORMAL_PRIORITY;
			} else {
				warning = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE";
				valueIsNull = false;
				priority = isChecked ? NORMAL_PRIORITY : LOW_PRIORITY;
			}

		} else {
			boolean bothNull = redundantBranch.firstValue.isDefinitelyNull()
					&& redundantBranch.secondValue.isDefinitelyNull();
			if (redundantBranch.secondValue.isChecked())
				isChecked = true;
			if (redundantBranch.secondValue.wouldHaveBeenAKaboom()) {
				wouldHaveBeenAKaboom = true;
				locationOfKaBoom = redundantBranch.secondValue
						.getLocationOfKaBoom();
			}
			if (bothNull) {
				warning = "RCN_REDUNDANT_COMPARISON_TWO_NULL_VALUES";
				priority = NORMAL_PRIORITY;
			} else {
				warning = "RCN_REDUNDANT_COMPARISON_OF_NULL_AND_NONNULL_VALUE";
				priority = isChecked ? NORMAL_PRIORITY : LOW_PRIORITY;
			}

		}

		if (wouldHaveBeenAKaboom) {
			priority = HIGH_PRIORITY;
			warning = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE";
			if (locationOfKaBoom == null)
				throw new NullPointerException("location of KaBoom is null");
		}

		if (DEBUG)
			System.out.println(createdDeadCode + " "
					+ infeasibleEdgeSimplyThrowsException + " " + valueIsNull
					+ " " + priority);
		if (createdDeadCode && !infeasibleEdgeSimplyThrowsException) {
			priority += 0;
		} else if (createdDeadCode && infeasibleEdgeSimplyThrowsException) {
			// throw clause
			if (valueIsNull)
				priority += 0;
			else
				priority += 1;
		} else {
			// didn't create any dead code
			priority += 1;
		}

		if (DEBUG) {
			System.out.println("RCN" + priority + " "
					+ redundantBranch.firstValue + " =? "
					+ redundantBranch.secondValue + " : " + warning);

			if (isChecked)
				System.out.println("isChecked");
			if (wouldHaveBeenAKaboom)
				System.out.println("wouldHaveBeenAKaboom");
			if (createdDeadCode)
				System.out.println("createdDeadCode");
		}
		if (priority > LOW_PRIORITY) return;
		BugAnnotation variableAnnotation = null;
		try {
			// Get the value number
			ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(
					method).getFactAtLocation(location);
			if (vnaFrame.isValid()) {
				Instruction ins = location.getHandle().getInstruction();

				ValueNumber valueNumber = vnaFrame.getInstance(ins,
						classContext.getConstantPoolGen());
				if (valueNumber.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT))
					return;
				variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method,
						location, valueNumber, vnaFrame);

			}
		} catch (DataflowAnalysisException e) {
			// ignore
		} catch (CFGBuilderException e) {
			// ignore
		}

		BugInstance bugInstance = new BugInstance(this, warning, priority)
				.addClassAndMethod(classContext.getJavaClass(), method);
		if (variableAnnotation != null)
			bugInstance.add(variableAnnotation);
		else
			bugInstance.add(new LocalVariableAnnotation("?", -1, -1));
		if (wouldHaveBeenAKaboom)
			bugInstance.addSourceLine(classContext, method,
					locationOfKaBoom);
		
		if (FindBugsAnalysisFeatures.isRelaxedMode()) {
			WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<WarningProperty>();
			WarningPropertyUtil.addPropertiesForLocation(propertySet,
					classContext, method, location);
			if (isChecked)
				propertySet.addProperty(NullDerefProperty.CHECKED_VALUE);
			if (wouldHaveBeenAKaboom)
				propertySet
						.addProperty(NullDerefProperty.WOULD_HAVE_BEEN_A_KABOOM);
			if (createdDeadCode)
				propertySet.addProperty(NullDerefProperty.CREATED_DEAD_CODE);

			propertySet.decorateBugInstance(bugInstance);

			priority = propertySet.computePriority(NORMAL_PRIORITY);
			bugInstance.setPriority(priority);
		}
		
		SourceLineAnnotation sourceLine = SourceLineAnnotation.fromVisitedInstruction(classContext, method,
				location);
		sourceLine.setDescription("SOURCE_REDUNDANT_NULL_CHECK");
		bugAccumulator.accumulateBug(bugInstance, sourceLine);
	}


	BugAnnotation getVariableAnnotation(Location location) {
		BugAnnotation variableAnnotation = null;
		try {
			// Get the value number
			ValueNumberFrame vnaFrame = classContext.getValueNumberDataflow(
					method).getFactAtLocation(location);
			if (vnaFrame.isValid()) {
				Instruction ins = location.getHandle().getInstruction();

				ValueNumber valueNumber = vnaFrame.getInstance(ins,
						classContext.getConstantPoolGen());
				if (valueNumber.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT))
					return null;
				variableAnnotation = ValueNumberSourceInfo.findAnnotationFromValueNumber(method,
						location, valueNumber, vnaFrame);

			}
		} catch (DataflowAnalysisException e) {
			// ignore
		} catch (CFGBuilderException e) {
			// ignore
		}
		return variableAnnotation;

	}
	/**
	 * Determine whether or not given instruction is a goto.
	 * 
	 * @param instruction
	 *            the instruction
	 * @return true if the instruction is a goto, false otherwise
	 */
	private boolean isGoto(Instruction instruction) {
		return instruction.getOpcode() == Constants.GOTO
				|| instruction.getOpcode() == Constants.GOTO_W;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see edu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonCollector#foundGuaranteedNullDeref(java.util.Set,
	 *      java.util.Set, edu.umd.cs.findbugs.ba.vna.ValueNumber, boolean)
	 */
	public void foundGuaranteedNullDeref(
			@NonNull Set<Location> assignedNullLocationSet, 
			@NonNull Set<Location> derefLocationSet, 
			SortedSet<Location> doomedLocations,
			ValueNumberDataflow vna, ValueNumber refValue,
			@CheckForNull BugAnnotation variableAnnotation, NullValueUnconditionalDeref deref,
			boolean npeIfStatementCovered) {
		if (refValue.hasFlag(ValueNumber.CONSTANT_CLASS_OBJECT))
			return;

		if (DEBUG) {
			System.out.println("Found guaranteed null deref in "
					+ method.getName());
			for (Location loc : doomedLocations)
				System.out.println("Doomed at " + loc);
		}

		String bugType = "NP_GUARANTEED_DEREF";
		int priority = NORMAL_PRIORITY;

		if (deref.isMethodReturnValue())
			bugType = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE";
		else {
			if (deref.isAlwaysOnExceptionPath())
				bugType += "_ON_EXCEPTION_PATH";
			else priority = HIGH_PRIORITY;
			if (!npeIfStatementCovered)
				priority++;
		}

		// Add Locations in the set of locations at least one of which
		// is guaranteed to be dereferenced

		SortedSet<Location> sourceLocations;
		if (doomedLocations.isEmpty() || doomedLocations.size() > 3
				&& doomedLocations.size() > assignedNullLocationSet.size())
			sourceLocations = new TreeSet<Location>(assignedNullLocationSet);
		else
			sourceLocations = doomedLocations;

		if (doomedLocations.isEmpty() || derefLocationSet.isEmpty())
			return;
		boolean derefOutsideCatchBlock = false;
		for (Location loc : derefLocationSet)
			if (!inCatchNullBlock(loc)) {
				derefOutsideCatchBlock = true;
				break;
			}

		boolean uniqueDereferenceLocations = false;
		LineNumberTable table = method.getLineNumberTable();
		if (table == null)
			uniqueDereferenceLocations = true;
		else {
			BitSet linesMentionedMultipleTimes = ClassContext.linesMentionedMultipleTimes(method);
			for(Location loc : derefLocationSet) {
			  int lineNumber = table.getSourceLine(loc.getHandle().getPosition());
			  if (!linesMentionedMultipleTimes.get(lineNumber)) uniqueDereferenceLocations = true;
			}
		}


		if (!derefOutsideCatchBlock) {
			if (!uniqueDereferenceLocations || skipIfInsideCatchNull())
				return;
			priority++;
		}
		if (!uniqueDereferenceLocations)
			priority++;

		// Create BugInstance

		BitSet knownNull = new BitSet();

		SortedSet<SourceLineAnnotation> knownNullLocations = new TreeSet<SourceLineAnnotation>();
		for (Location loc : sourceLocations) {
			SourceLineAnnotation sourceLineAnnotation = SourceLineAnnotation
					.fromVisitedInstruction(classContext, method, loc);
			if (sourceLineAnnotation == null)
				continue;
			int startLine = sourceLineAnnotation.getStartLine();
			if (startLine == -1)
				knownNullLocations.add(sourceLineAnnotation);
			else if (!knownNull.get(startLine)) {
				knownNull.set(startLine);
				knownNullLocations.add(sourceLineAnnotation);
			}
		}

		FieldAnnotation storedField = null;
		MethodAnnotation invokedMethod = null;
		XMethod invokedXMethod = null;
		int parameterNumber = -1;
		if (derefLocationSet.size() == 1) {
			Location loc = derefLocationSet.iterator().next();

			PointerUsageRequiringNonNullValue pu = null;
			try {
				UsagesRequiringNonNullValues usages = classContext.getUsagesRequiringNonNullValues(method);
				pu = usages.get(loc, refValue, vnaDataflow);
			} catch (DataflowAnalysisException e) {
			   AnalysisContext.logError("Error getting UsagesRequiringNonNullValues for " + method, e);
			} catch (CFGBuilderException e) {
				AnalysisContext.logError("Error getting UsagesRequiringNonNullValues for " + method, e);
			}


			if (pu != null) {


			if (pu.getReturnFromNonNullMethod()) {
				bugType = "NP_NONNULL_RETURN_VIOLATION";
				String methodName = method.getName();
				String methodSig = method.getSignature();
				if (methodName.equals("clone") && methodSig.equals("()Ljava/lang/Object;")) {
					bugType = "NP_CLONE_COULD_RETURN_NULL";
					priority = NORMAL_PRIORITY;
				} else if (methodName.equals("toString") && methodSig.equals("()Ljava/lang/String;")) {
					bugType = "NP_TOSTRING_COULD_RETURN_NULL";
					priority = NORMAL_PRIORITY;
				}

			} else {
				XField nonNullField = pu.getNonNullField();
				if (nonNullField != null) {
					storedField =  FieldAnnotation.fromXField( nonNullField );
					bugType = "NP_STORE_INTO_NONNULL_FIELD";
				} else {
					XMethodParameter nonNullParameter = pu.getNonNullParameter();
					if (nonNullParameter != null) {
						XMethodParameter mp = nonNullParameter ;
						invokedXMethod = mp.getMethod();
						invokedMethod =  MethodAnnotation.fromXMethod(mp.getMethod());
						parameterNumber = mp.getParameterNumber();
						bugType = "NP_NULL_PARAM_DEREF";
					}
				}
			}
			} else  if (!deref.isAlwaysOnExceptionPath())
				bugType = "NP_NULL_ON_SOME_PATH";
			else
				bugType = "NP_NULL_ON_SOME_PATH_EXCEPTION";

			if (deref.isReadlineValue())
				bugType = "NP_DEREFERENCE_OF_READLINE_VALUE";
			else if (deref.isMethodReturnValue())
				bugType = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE";

		}


		XMethod xMethod = XFactory.createXMethod(classContext.getJavaClass(), method);
		boolean uncallable = !AnalysisContext.currentXFactory().isCalledDirectlyOrIndirectly(xMethod) 
				&& xMethod.isPrivate();
		if (invokedXMethod != null) for (Location derefLoc : derefLocationSet) 
			if (safeCallToPrimateParseMethod(invokedXMethod, derefLoc)) return;
		boolean hasManyNullTests = true;
		for (SourceLineAnnotation sourceLineAnnotation : knownNullLocations) {
			if (!hasManyPreceedingNullTests(sourceLineAnnotation.getStartBytecode()))
					hasManyNullTests = false;
		}
		if (hasManyNullTests) {
			if (bugType.equals("NP_NULL_ON_SOME_PATH"))
					bugType = "NP_NULL_ON_SOME_PATH_MIGHT_BE_INFEASIBLE";
			else priority++;
		}
			
		BugInstance bugInstance = new BugInstance(this, bugType, priority)
				.addClassAndMethod(classContext.getJavaClass(), method);
		if (invokedMethod != null)
			bugInstance.addMethod(invokedMethod).describe("METHOD_CALLED")
			.addInt(parameterNumber+1).describe("INT_MAYBE_NULL_ARG");
		if (storedField!= null)
			bugInstance.addField(storedField).describe("FIELD_STORED");
		bugInstance.addOptionalAnnotation(variableAnnotation);
		if (variableAnnotation instanceof FieldAnnotation)
			bugInstance.describe("FIELD_CONTAINS_VALUE");
		
		// If all deref locations are doomed
		// (i.e., locations where a normal return is not possible),
		// add a warning property indicating such.

		// Are all derefs at doomed locations?
		boolean allDerefsAtDoomedLocations = true;
		for (Location derefLoc : derefLocationSet) {
			if (!isDoomed(derefLoc)) {
				allDerefsAtDoomedLocations = false;
				break;
			}
		}

		WarningPropertySet<WarningProperty> propertySet = new WarningPropertySet<WarningProperty>();

		if (allDerefsAtDoomedLocations) {
			// Add a WarningProperty
			propertySet.addProperty(DoomedCodeWarningProperty.DOOMED_CODE);
		}
		if (uncallable)
			propertySet.addProperty(GeneralWarningProperty.IN_UNCALLABLE_METHOD);
		propertySet.decorateBugInstance(bugInstance);
		
		if (bugType.equals("NP_DEREFERENCE_OF_READLINE_VALUE")) {

			int source = -9999;
			if (knownNullLocations.size() == 1)
				source = knownNullLocations.iterator().next().getEndBytecode();
			for (Location loc : derefLocationSet) {
				int pos = loc.getHandle().getPosition();
				if (pos != source+3) // immediate dereferences are handled by another detector
					bugAccumulator.accumulateBug(bugInstance, SourceLineAnnotation.fromVisitedInstruction(classContext, method, loc));
			}

		} else {
			for (Location loc : derefLocationSet)
				bugInstance.addSourceLine(classContext, method, loc).describe(getDescription(loc, refValue));

			for (SourceLineAnnotation sourceLineAnnotation : knownNullLocations) {
				bugInstance.add(sourceLineAnnotation).describe(
				"SOURCE_LINE_KNOWN_NULL");
			}

			// Report it
			bugReporter.reportBug(bugInstance);
		}
	}

	private boolean isDoomed(Location loc) {
		if (!MARK_DOOMED) {
			return false;
		}

		ReturnPathTypeDataflow rptDataflow;
		try {
			rptDataflow = classContext.getReturnPathTypeDataflow(method);

			ReturnPathType rpt = rptDataflow.getFactAtLocation(loc);

			return  !rpt.canReturnNormally();
		} catch (CheckedAnalysisException e) {
			AnalysisContext.logError("Error getting return path type", e);
			return false;
		}
	}

	String getDescription(Location loc, ValueNumber refValue) {
		PointerUsageRequiringNonNullValue pu;
		try {
			UsagesRequiringNonNullValues usages = classContext.getUsagesRequiringNonNullValues(method);
			pu = usages.get(loc, refValue, vnaDataflow);
			if (pu == null)  return "SOURCE_LINE_DEREF";
			return pu.getDescription();
		} catch (DataflowAnalysisException e) {
		   AnalysisContext.logError("Error getting UsagesRequiringNonNullValues for " + method, e);
		   return "SOURCE_LINE_DEREF";
		} catch (CFGBuilderException e) {
			AnalysisContext.logError("Error getting UsagesRequiringNonNullValues for " + method, e);
			return "SOURCE_LINE_DEREF";
		}

	}
	boolean inCatchNullBlock(Location loc) {
		int pc = loc.getHandle().getPosition();
		int catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
				"java/lang/NullPointerException", pc);
		if (catchSize < Integer.MAX_VALUE)
			return true;
		catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
				"java/lang/Exception", pc);
		if (catchSize < 5)
			return true;
		catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
				"java/lang/RuntimeException", pc);
		if (catchSize < 5)
			return true;
		catchSize = Util.getSizeOfSurroundingTryBlock(classContext.getJavaClass().getConstantPool(), method.getCode(),
				"java/lang/Throwable", pc);
		if (catchSize < 5)
			return true;
		return false;

	}
}

// vim:ts=4
