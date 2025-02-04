/*
 * Tencent is pleased to support the open source community by making Polaris available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.polaris.dubbox.circuitbreaker;


import java.util.concurrent.TimeUnit;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.tencent.polaris.api.plugin.circuitbreaker.ResourceStat;
import com.tencent.polaris.api.plugin.circuitbreaker.entity.InstanceResource;
import com.tencent.polaris.api.plugin.circuitbreaker.entity.Resource;
import com.tencent.polaris.api.pojo.RetStatus;
import com.tencent.polaris.api.pojo.ServiceKey;
import com.tencent.polaris.api.utils.StringUtils;
import com.tencent.polaris.circuitbreak.api.CircuitBreakAPI;
import com.tencent.polaris.circuitbreak.api.InvokeHandler;
import com.tencent.polaris.circuitbreak.api.pojo.InvokeContext;
import com.tencent.polaris.circuitbreak.api.pojo.ResultToErrorCode;
import com.tencent.polaris.circuitbreak.client.exception.CallAbortedException;
import com.tencent.polaris.common.exception.PolarisBlockException;
import com.tencent.polaris.common.registry.PolarisOperator;
import com.tencent.polaris.common.registry.PolarisOperatorDelegate;

@Activate(group = Constants.CONSUMER)
public class CircuitBreakerFilter extends PolarisOperatorDelegate implements Filter, ResultToErrorCode {

	@Override
	public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		PolarisOperator polarisOperator = getPolarisOperator();
		if (null == polarisOperator) {
			return invoker.invoke(invocation);
		}

		CircuitBreakAPI circuitBreakAPI = getPolarisOperator().getCircuitBreakAPI();
		InvokeContext.RequestContext context = new InvokeContext.RequestContext(createCalleeService(invoker), invocation.getMethodName());
		context.setResultToErrorCode(this);
		InvokeHandler handler = circuitBreakAPI.makeInvokeHandler(context);
		try {
			long startTimeMilli = System.currentTimeMillis();
			InvokeContext.ResponseContext responseContext = new InvokeContext.ResponseContext();
			responseContext.setDurationUnit(TimeUnit.MILLISECONDS);
			Result result = null;
			RpcException exception = null;
			handler.acquirePermission();
			try {
				result = invoker.invoke(invocation);
				responseContext.setDuration(System.currentTimeMillis() - startTimeMilli);
				if (result.hasException()) {
					responseContext.setError(result.getException());
					handler.onError(responseContext);
				}
				else {
					responseContext.setResult(result);
					handler.onSuccess(responseContext);
				}
			}
			catch (RpcException e) {
				exception = e;
				responseContext.setError(e);
				responseContext.setDuration(System.currentTimeMillis() - startTimeMilli);
				handler.onError(responseContext);
			}
			ResourceStat resourceStat = createInstanceResourceStat(invoker, invocation, responseContext, responseContext.getDuration());
			circuitBreakAPI.report(resourceStat);
			if (result != null) {
				return result;
			}
			throw exception;
		}
		catch (CallAbortedException abortedException) {
			throw new RpcException(abortedException);
		}
	}

	private ResourceStat createInstanceResourceStat(Invoker<?> invoker, Invocation invocation,
			InvokeContext.ResponseContext context, long delay) {
		URL url = invoker.getUrl();
		Throwable exception = context.getError();
		RetStatus retStatus = RetStatus.RetSuccess;
		int code = 0;
		if (null != exception) {
			retStatus = RetStatus.RetFail;
			if (exception instanceof RpcException) {
				RpcException rpcException = (RpcException) exception;
				code = rpcException.getCode();
				if (StringUtils.isNotBlank(rpcException.getMessage()) && rpcException.getMessage()
						.contains(PolarisBlockException.PREFIX)) {
					// 限流异常不进行熔断
					retStatus = RetStatus.RetFlowControl;
				}
				if (rpcException.isTimeout()) {
					retStatus = RetStatus.RetTimeout;
				}
			}

			else {
				code = -1;
			}
		}

		ServiceKey calleeServiceKey = createCalleeService(invoker);
		Resource resource = new InstanceResource(
				calleeServiceKey,
				url.getHost(),
				url.getPort(),
				new ServiceKey()
		);
		return new ResourceStat(resource, code, delay, retStatus);
	}

	private ServiceKey createCalleeService(Invoker<?> invoker) {
		URL url = invoker.getUrl();
		return new ServiceKey(getPolarisOperator().getPolarisConfig().getNamespace(), url.getServiceInterface());
	}

	@Override
	public int onSuccess(Object value) {
		return 0;
	}

	@Override
	public int onError(Throwable throwable) {
		int code = 0;
		if (throwable instanceof RpcException) {
			RpcException rpcException = (RpcException) throwable;
			code = rpcException.getCode();
		}
		else {
			code = -1;
		}
		return code;
	}
}
