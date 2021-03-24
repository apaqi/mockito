/*
 * Copyright (c) 2007 Mockito contributors
 * This program is made available under the terms of the MIT License.
 */
package org.mockito.internal.handler;

import org.mockito.internal.creation.settings.CreationSettings;
import org.mockito.internal.invocation.InvocationMatcher;
import org.mockito.internal.invocation.MatchersBinder;
import org.mockito.internal.stubbing.InvocationContainerImpl;
import org.mockito.internal.stubbing.OngoingStubbingImpl;
import org.mockito.internal.stubbing.StubbedInvocationMatcher;
import org.mockito.internal.stubbing.answers.DefaultAnswerValidator;
import org.mockito.internal.verification.MockAwareVerificationMode;
import org.mockito.internal.verification.VerificationDataImpl;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationContainer;
import org.mockito.invocation.MockHandler;
import org.mockito.mock.MockCreationSettings;
import org.mockito.verification.VerificationMode;

import static org.mockito.internal.listeners.StubbingLookupNotifier.notifyStubbedAnswerLookup;
import static org.mockito.internal.progress.ThreadSafeMockingProgress.mockingProgress;

/**
 * Invocation handler set on mock objects.
 *
 * @param <T> type of mock object to handle
 */
public class MockHandlerImpl<T> implements MockHandler<T> {

    private static final long serialVersionUID = -2917871070982574165L;

    InvocationContainerImpl invocationContainer;

    MatchersBinder matchersBinder = new MatchersBinder();

    private final MockCreationSettings<T> mockSettings;

    public MockHandlerImpl(MockCreationSettings<T> mockSettings) {
        this.mockSettings = mockSettings;

        this.matchersBinder = new MatchersBinder();
        this.invocationContainer = new InvocationContainerImpl(mockSettings);
    }

    /**
     * 方法调用时首先根据InterceptedInvocation对象查找对应的stub（StubbedInvocationMatcher），
     * 如果找到则使用该stub返回特定值，否则返回默认值（一般为null）
     * @param invocation The invocation to handle
     * @return
     * @throws Throwable
     */
    public Object handle(Invocation invocation) throws Throwable {
        if (invocationContainer.hasAnswersForStubbing()) {
            // stubbing voids with doThrow() or doAnswer() style
            InvocationMatcher invocationMatcher = matchersBinder.bindMatchers(
                    mockingProgress().getArgumentMatcherStorage(),
                    invocation
            );
            invocationContainer.setMethodForStubbing(invocationMatcher);
            return null;
        }
        VerificationMode verificationMode = mockingProgress().pullVerificationMode();

        InvocationMatcher invocationMatcher = matchersBinder.bindMatchers(
                mockingProgress().getArgumentMatcherStorage(),
                invocation
        );
        /**
         * 校验当前mockito当前处理状态，比如执行了两次 Mockito.when()，由于第一次Mockito.when()没有调用thenReturn
         *所以第二次会报异常，就是这里判断的，MockitoProgress中有一个字段stubbingInProgress记录当前正在处理的Mockito代码信息
         *对应类LocationImpl */
        mockingProgress().validateState();

        // if verificationMode is not null then someone is doing verify()
        // 校验，比如timers/nerver/atLeast校验等
        if (verificationMode != null) {
            // We need to check if verification was started on the correct mock
            // - see VerifyingWithAnExtraCallToADifferentMockTest (bug 138)
            if (((MockAwareVerificationMode) verificationMode).getMock() == invocation.getMock()) {
                VerificationDataImpl data = new VerificationDataImpl(invocationContainer, invocationMatcher);
                verificationMode.verify(data);
                return null;
            } else {
                // this means there is an invocation on a different mock. Re-adding verification mode
                // - see VerifyingWithAnExtraCallToADifferentMockTest (bug 138)
                mockingProgress().verificationStarted(verificationMode);
            }
        }

        // prepare invocation for stubbing
        // 打桩准备，保存当前invocation信息，ongoingStubbing保存到ThreadLocal中
        invocationContainer.setInvocationForPotentialStubbing(invocationMatcher);
        OngoingStubbingImpl<T> ongoingStubbing = new OngoingStubbingImpl<T>(invocationContainer);
        /**
         * 保存当前执行的函数信息，以及相关参数的信息，当使用 when 函数进行函数调用返回值的固定方式就通过 mockingProcess() 中获取 OngoingStubbing 对象，
         * 并设定相应的返回参数即可完成返回默认值或固定值的操作。
         */
        mockingProgress().reportOngoingStubbing(ongoingStubbing);

        // look for existing answer for this invocation
        // 根据invocation匹配对应的stub，匹配规则是 class相同+方法签名相同+入参匹配
        StubbedInvocationMatcher stubbing = invocationContainer.findAnswerFor(invocation);
        // TODO #793 - when completed, we should be able to get rid of the casting below
        notifyStubbedAnswerLookup(invocation, stubbing, invocationContainer.getStubbingsAscending(),
                                  (CreationSettings) mockSettings);
        // 返回默认值或者返回自定义返回值
        if (stubbing != null) {
            stubbing.captureArgumentsFrom(invocation);

            try {
                return stubbing.answer(invocation);
            } finally {
                //Needed so that we correctly isolate stubbings in some scenarios
                //see MockitoStubbedCallInAnswerTest or issue #1279
                mockingProgress().reportOngoingStubbing(ongoingStubbing);
            }
        } else {
            Object ret = mockSettings.getDefaultAnswer().answer(invocation);
            DefaultAnswerValidator.validateReturnValueFor(invocation, ret);

            //Mockito uses it to redo setting invocation for potential stubbing in case of partial mocks / spies.
            //Without it, the real method inside 'when' might have delegated to other self method
            //and overwrite the intended stubbed method with a different one.
            //This means we would be stubbing a wrong method.
            //Typically this would led to runtime exception that validates return type with stubbed method signature.
            invocationContainer.resetInvocationForPotentialStubbing(invocationMatcher);
            return ret;
        }
    }

    public MockCreationSettings<T> getMockSettings() {
        return mockSettings;
    }

    public InvocationContainer getInvocationContainer() {
        return invocationContainer;
    }
}
