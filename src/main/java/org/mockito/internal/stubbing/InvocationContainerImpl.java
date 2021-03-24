/*
 * Copyright (c) 2007 Mockito contributors
 * This program is made available under the terms of the MIT License.
 */
package org.mockito.internal.stubbing;

import org.mockito.internal.invocation.StubInfoImpl;
import org.mockito.internal.verification.DefaultRegisteredInvocations;
import org.mockito.internal.verification.RegisteredInvocations;
import org.mockito.internal.verification.SingleRegisteredInvocation;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationContainer;
import org.mockito.invocation.MatchableInvocation;
import org.mockito.mock.MockCreationSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubbing;
import org.mockito.stubbing.ValidableAnswer;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.mockito.internal.progress.ThreadSafeMockingProgress.mockingProgress;

@SuppressWarnings("unchecked")
public class InvocationContainerImpl implements InvocationContainer, Serializable {

    private static final long serialVersionUID = -5334301962749537177L;
    private final LinkedList<StubbedInvocationMatcher> stubbed = new LinkedList<StubbedInvocationMatcher>();
    private final DoAnswerStyleStubbing doAnswerStyleStubbing;
    private final RegisteredInvocations registeredInvocations;
    private final Strictness mockStrictness;

    private MatchableInvocation invocationForStubbing;

    public InvocationContainerImpl(MockCreationSettings mockSettings) {
        this.registeredInvocations = createRegisteredInvocations(mockSettings);
        this.mockStrictness = mockSettings.isLenient() ? Strictness.LENIENT : null;
        this.doAnswerStyleStubbing = new DoAnswerStyleStubbing();
    }

    public void setInvocationForPotentialStubbing(MatchableInvocation invocation) {
        registeredInvocations.add(invocation.getInvocation());
        this.invocationForStubbing = invocation;
    }

    public void resetInvocationForPotentialStubbing(MatchableInvocation invocationMatcher) {
        this.invocationForStubbing = invocationMatcher;
    }

    public void addAnswer(Answer answer, Strictness stubbingStrictness) {
        // 移除最近一次方法调用保存的invocation信息
        registeredInvocations.removeLast();
        addAnswer(answer, false, stubbingStrictness);
    }

    public void addConsecutiveAnswer(Answer answer) {
        addAnswer(answer, true, null);
    }

    /**
     * Adds new stubbed answer and returns the invocation matcher the answer was added to.
     */
    public StubbedInvocationMatcher addAnswer(Answer answer, boolean isConsecutive, Strictness stubbingStrictness) {
        Invocation invocation = invocationForStubbing.getInvocation();
        mockingProgress().stubbingCompleted();
        if (answer instanceof ValidableAnswer) {
            ((ValidableAnswer) answer).validateFor(invocation);
        }
        /**
         * 将打桩的自定义返回值添加到stubbed中之后，就可以在方法调用中根据方法+入参从stubbed中匹配自定义的返回值。
         * 这里有一个连续方式和非连续方式，是什么意思呢，连续方式就是说，针对同一个方法的多次调用，
         * 可以按照顺序自定义不同的返回值，如果调用次数超过了自定义返回值个数，默认后续以最后一个自定义返回值为准
         *
         *DubboRemoteFacade remoteFacade = Mockito.mock(DubboRemoteFacade.class);
         * Mockito.when(remoteFacade.world("aaa")).thenReturn("111").thenReturn("222"); // 连续方式
         * System.out.println(remoteFacade.world("aaa")); // 111
         * System.out.println(remoteFacade.world("aaa")); // 222
         * //System.out.println(remoteFacade.world("aaa")); // 222，如果再调用一次的话
         */
        synchronized (stubbed) {
            // 连续方式
            if (isConsecutive) {
                stubbed.getFirst().addAnswer(answer);
            } else {// 非连续
                Strictness effectiveStrictness = stubbingStrictness != null ? stubbingStrictness : this.mockStrictness;
                stubbed.addFirst(new StubbedInvocationMatcher(answer, invocationForStubbing, effectiveStrictness));
            }
            return stubbed.getFirst();
        }
    }

    Object answerTo(Invocation invocation) throws Throwable {
        return findAnswerFor(invocation).answer(invocation);
    }

    public StubbedInvocationMatcher findAnswerFor(Invocation invocation) {
        synchronized (stubbed) {
            for (StubbedInvocationMatcher s : stubbed) {
                if (s.matches(invocation)) {
                    s.markStubUsed(invocation);
                    //TODO we should mark stubbed at the point of stubbing, not at the point where the stub is being used
                    invocation.markStubbed(new StubInfoImpl(s));
                    return s;
                }
            }
        }

        return null;
    }

    /**
     * Sets the answers declared with 'doAnswer' style.
     */
    public void setAnswersForStubbing(List<Answer<?>> answers, Strictness strictness) {
        doAnswerStyleStubbing.setAnswers(answers, strictness);
    }

    public boolean hasAnswersForStubbing() {
        return !doAnswerStyleStubbing.isSet();
    }

    public boolean hasInvocationForPotentialStubbing() {
        return !registeredInvocations.isEmpty();
    }

    public void setMethodForStubbing(MatchableInvocation invocation) {
        invocationForStubbing = invocation;
        assert hasAnswersForStubbing();
        for (int i = 0; i < doAnswerStyleStubbing.getAnswers().size(); i++) {
            addAnswer(doAnswerStyleStubbing.getAnswers().get(i), i != 0, doAnswerStyleStubbing.getStubbingStrictness());
        }
        doAnswerStyleStubbing.clear();
    }

    @Override
    public String toString() {
        return "invocationForStubbing: " + invocationForStubbing;
    }

    public List<Invocation> getInvocations() {
        return registeredInvocations.getAll();
    }

    public void clearInvocations() {
        registeredInvocations.clear();
    }

    /**
     * Stubbings in descending order, most recent first
     */
    public List<Stubbing> getStubbingsDescending() {
        return (List) stubbed;
    }

    /**
     * Stubbings in ascending order, most recent last
     */
    public Collection<Stubbing> getStubbingsAscending() {
        List<Stubbing> result = new LinkedList<Stubbing>(stubbed);
        Collections.reverse(result);
        return result;
    }

    public Object invokedMock() {
        return invocationForStubbing.getInvocation().getMock();
    }

    public MatchableInvocation getInvocationForStubbing() {
        return invocationForStubbing;
    }

    private RegisteredInvocations createRegisteredInvocations(MockCreationSettings mockSettings) {
        return mockSettings.isStubOnly()
          ? new SingleRegisteredInvocation()
          : new DefaultRegisteredInvocations();
    }
}
