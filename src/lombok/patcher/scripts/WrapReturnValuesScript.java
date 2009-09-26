/*
 * Copyright © 2009 Reinier Zwitserloot and Roel Spilker.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.patcher.scripts;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import lombok.NonNull;
import lombok.ToString;
import lombok.patcher.Hook;
import lombok.patcher.MethodLogistics;
import lombok.patcher.MethodTarget;
import lombok.patcher.PatchScript;
import lombok.patcher.StackRequest;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Will find every 'return' instruction in the target method and will insert right before it a call to the wrapper.
 * The wrapper will be given the instance (null if the target is static), and the value that would be returned.
 */
@ToString
public final class WrapReturnValuesScript extends PatchScript {
	private final @NonNull MethodTarget targetMethod;
	private final @NonNull Hook wrapper;
	private final Set<StackRequest> requests;
	private final boolean hijackReturnValue;
	
	public WrapReturnValuesScript(MethodTarget targetMethod, Hook wrapper, StackRequest... requests) {
		if (targetMethod == null) throw new NullPointerException("targetMethod");
		if (wrapper == null) throw new NullPointerException("wrapper");
		this.targetMethod = targetMethod;
		this.wrapper = wrapper;
		this.hijackReturnValue = !wrapper.getMethodDescriptor().endsWith(")V");
		this.requests = new HashSet<StackRequest>(Arrays.asList(requests));
	}
	
	@Override public byte[] patch(String className, byte[] byteCode) {
		if (!targetMethod.classMatches(className)) return null;
		return runASM(byteCode, true);
	}
	
	@Override protected ClassVisitor createClassVisitor(ClassWriter writer) {
		MethodPatcher patcher = new MethodPatcher(writer, new MethodPatcherFactory() {
			@Override public MethodVisitor createMethodVisitor(MethodTarget target, MethodVisitor parent, MethodLogistics logistics) {
				return new WrapReturnValues(parent, logistics);
			}
		});
		
		patcher.addMethodTarget(targetMethod);
		return patcher;
	}
	
	private class WrapReturnValues extends MethodAdapter {
		private final MethodLogistics logistics;
		
		public WrapReturnValues(MethodVisitor mv, MethodLogistics logistics) {
			super(mv);
			this.logistics = logistics;
		}
		
		@Override public void visitInsn(int opcode) {
			if (opcode != logistics.getReturnOpcode()) {
				super.visitInsn(opcode);
				return;
			}
			
			if (requests.contains(StackRequest.RETURN_VALUE)) {
				if (!hijackReturnValue) {
					//The supposed return value is on stack, but the wrapper wants it and will not supply a new one, so duplicate it.
					logistics.generateDupForReturn(mv);
				}
			} else {
				if (hijackReturnValue) {
					//The supposed return value is on stack, but the wrapper doesn't want it and will supply a new one, so, kill it.
					logistics.generatePopForReturn(mv);
				}
			}
			
			if (requests.contains(StackRequest.THIS)) logistics.generateLoadOpcodeForThis(mv);
			
			for (StackRequest param : StackRequest.PARAMS_IN_ORDER) {
				if (!requests.contains(param)) continue;
				logistics.generateLoadOpcodeForParam(param.getParamPos(), mv);
			}
			
			super.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper.getClassSpec(), wrapper.getMethodName(),
					wrapper.getMethodDescriptor());
			super.visitInsn(opcode);
		}
	}
}
