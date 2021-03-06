package com.yiqiniu.easytrans.test;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Resource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import com.yiqiniu.easytrans.core.ConsistentGuardian;
import com.yiqiniu.easytrans.core.EasytransConstant;
import com.yiqiniu.easytrans.log.TransactionLogReader;
import com.yiqiniu.easytrans.log.impl.database.DataBaseTransactionLogConfiguration.DataBaseForLog;
import com.yiqiniu.easytrans.log.vo.LogCollection;
import com.yiqiniu.easytrans.protocol.BusinessIdentifer;
import com.yiqiniu.easytrans.protocol.TransactionId;
import com.yiqiniu.easytrans.rpc.EasyTransRpcConsumer;
import com.yiqiniu.easytrans.test.mockservice.accounting.AccountingService;
import com.yiqiniu.easytrans.test.mockservice.accounting.easytrans.AccountingCpsMethod.AccountingRequest;
import com.yiqiniu.easytrans.test.mockservice.express.ExpressService;
import com.yiqiniu.easytrans.test.mockservice.express.easytrans.ExpressDeliverAfterTransMethod.ExpressDeliverAfterTransMethodRequest;
import com.yiqiniu.easytrans.test.mockservice.order.NotReliableOrderMessage;
import com.yiqiniu.easytrans.test.mockservice.order.OrderMessage;
import com.yiqiniu.easytrans.test.mockservice.order.OrderService;
import com.yiqiniu.easytrans.test.mockservice.order.OrderService.UtProgramedException;
import com.yiqiniu.easytrans.test.mockservice.point.PointService;
import com.yiqiniu.easytrans.test.mockservice.wallet.WalletService;
import com.yiqiniu.easytrans.test.mockservice.wallet.easytrans.WalletPayTccMethod.WalletPayTccMethodRequest;

@RunWith(SpringRunner.class)
@SpringBootTest(classes={EasyTransTestConfiguration.class})
public class FullTest {
	
	
	@Resource(name="wholeJdbcTemplate")
	private JdbcTemplate wholeJdbcTemplate;
	
	@Resource
	OrderService orderService;
	
	@Resource
	private ConsistentGuardian guardian;
	
	@Resource
	private TransactionLogReader logReader;
	

	@Value("${spring.application.name}")
	private String applicationName;
	
	@Resource
	private EasyTransRpcConsumer consumer;
	
	@Resource
	private AccountingService accountingService;
	@Resource
	private ExpressService expressService;
	@Resource
	private PointService pointService;
	@Resource
	private WalletService walletService;
	@Resource
	private DataBaseForLog dbForLog;
	
	private ExecutorService executor = Executors.newFixedThreadPool(4);
//	private ExecutorService executor = Executors.newFixedThreadPool(1);
	
	private Logger LOG = LoggerFactory.getLogger(this.getClass());
	
	private int concurrentTestId = 100000;
	
	/**
	 * 测试本方法需要先配置好数据库及kafka
	 * 在trxtest库中需要创建 executed_trans、idempotent表，建表语句在readme.md中，其余测试业务相关表会自动创建
	 * （基于数据库的事务日志）在translog库中需要创建trans_log_unfinished，trans_log_detail，建表语句也在readme.md中
	 * （基于KAFKA的消息队列）在kafka中创建以下队列,对应的复制策略，灾备等请自行考虑调整。（如果kafka没有配置自动创建TOPIC）
	 * ./kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 3 --topic trx-test-service_ReliableOrderMsg
	 * 
	 * 
	 * 测试案例中使用了多个指向同一个数据库的业务数据源模拟分布式事务的场景，这是为了测试方便。
	 * 我们也可以使用多个数据源对应不同的数据库，并启动多个进程进行测试，但需要各位自行调整测试代码
	 * 
	 * 本测试在执行过程中可能会打印出很多异常信息，但同时也有很多异常是设定好，必须发生的，因此判断ut是否成功，只看最后的assert
	 */
	@Test
	public void test(){


		//synchronizer test
		//清除创建测试初始数据
		cleanAndSetUp();
		//测试主事务成功，从事务也全部成功的场景
		commitedAndSubTransSuccess();
		
		//测试在执行COMMIT前发生异常的场景
		rollbackWithExceptionJustBeforeCommit();
		//在主事务中，远程调用执行了一半后发生异常的场景
		rollbackWithExceptionInMiddle();
		//在主事务中没有执行过任何远程调用就发生了异常的场景
		rollbackWithExceptionJustAfterStartEasyTrans();

		//consistent guardian test
		//主事务提交了，在跟进最终一致性的时候发生异常的场景
		commitWithExceptionInMiddleOfConsistenGuardian();
		//主事务回滚了，在跟进相关补偿回滚操作时候发生异常的场景
		rollbackWithExceptionInMiddleOfConsistenGuardian();
		
		//idempotent test
		//激活线程池
		activateThreadPool();
		//并发执行TCC操作
		sameMethodConcurrentTcc();
		//并发执行可补偿操作
		differentMethodConcurrentCompensable();
		
		//测试单项功能
		executeTccOnly();
		executeCompensableOnly();
		executeAfterTransMethodOnly();
		executeReliableMsgOnly();
		executeNotReliableMessageOnly();
		
		//测试队列消费失败情况
		testMessageQueueConsumeFailue();
		
		//执行一遍后台补偿任务，以避免上述操作有未补偿成功的
		//execute consistent guardian in case of timeout
		List<LogCollection> unfinishedLogs = logReader.getUnfinishedLogs(null, 100, new Date());
		for(LogCollection logCollection:unfinishedLogs){
			guardian.process(logCollection);
		}
		
		sleep(20000);//wait for msg queue retry test finished
		
		Assert.assertTrue(walletService.getUserTotalAmount(1) == 7000);
		Assert.assertTrue(walletService.getUserFreezeAmount(1) == 0);
		Assert.assertTrue(accountingService.getTotalCost(1) == 3000);
		Assert.assertTrue(expressService.getUserExpressCount(1) == 3);
		Assert.assertTrue(pointService.getUserPoint(1) == 5000);
		System.out.println("Test Passed!!");
	}

	public void sleep(long sleepTime) {
		try {
			//等待消息队列两消息送到
			Thread.sleep(sleepTime);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void testMessageQueueConsumeFailue() {
		
		//失败一次后成功
		pointService.setSuccessErrorCount(1);
		executeReliableMsgOnly();
		
		sleep(2000);
		pointService.setSuccessErrorCount(4);
		executeReliableMsgOnly();
		
	}
	
	private void executeNotReliableMessageOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
//		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
		orderService.buySomething(1, 1000);
		OrderService.clearNotExecuteSet();
	}
	
	private void executeAfterTransMethodOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
//		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
		orderService.buySomething(1, 1000);
		OrderService.clearNotExecuteSet();
	}
	
	private void executeReliableMsgOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
//		OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
		orderService.buySomething(1, 1000);
		OrderService.clearNotExecuteSet();
	}

	private void executeTccOnly() {
		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
//		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
		orderService.buySomething(1, 1000);
		OrderService.clearNotExecuteSet();
	}
	
	private void executeCompensableOnly() {
//		OrderService.setNotExecuteBusiness(AccountingRequest.class);
		OrderService.setNotExecuteBusiness(ExpressDeliverAfterTransMethodRequest.class);
		OrderService.setNotExecuteBusiness(NotReliableOrderMessage.class);
		OrderService.setNotExecuteBusiness(OrderMessage.class);
		OrderService.setNotExecuteBusiness(WalletPayTccMethodRequest.class);
		orderService.buySomething(1, 1000);
		OrderService.clearNotExecuteSet();
	}
	
	private void differentMethodConcurrentCompensable() {

		final BusinessIdentifer annotation = AccountingRequest.class.getAnnotation(BusinessIdentifer.class);
		final int i = concurrentTestId++;
		
		final AccountingRequest request = new AccountingRequest();
		request.setAmount(1000l);
		request.setUserId(1);
		TransactionId parentTrxId = new TransactionId(applicationName, "concurrentTest", String.valueOf(i));
		HashMap<String,Object> header = new HashMap<>();
		header.put(EasytransConstant.CallHeadKeys.TANSACTION_ID_KEY, parentTrxId);
		header.put(EasytransConstant.CallHeadKeys.CALL_SEQ, 1);
		
		Callable<Object> doCompensableBusinessRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "doCompensableBusiness", header,request);
			}
		};
		
		Callable<Object> compensationRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "compensation", header,request);
			}
		};
		
		List<Callable<Object>> asListTry = Arrays.asList(compensationRequest,doCompensableBusinessRequest,compensationRequest,doCompensableBusinessRequest,compensationRequest,doCompensableBusinessRequest,compensationRequest,doCompensableBusinessRequest);
		try {
			List<Future<Object>> invokeAll = executor.invokeAll(asListTry);
			for(Future<Object> future:invokeAll){
				try {
					System.out.println(future.get());
				} catch (ExecutionException e) {
					e.printStackTrace();
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void activateThreadPool() {
		Callable<Object> runnable = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return null;
			}
		};
		
		try {
			executor.invokeAll(Arrays.asList(runnable,runnable,runnable,runnable,runnable,runnable,runnable,runnable));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void sameMethodConcurrentTcc() {
		final BusinessIdentifer annotation = WalletPayTccMethodRequest.class.getAnnotation(BusinessIdentifer.class);
		final int i = concurrentTestId++;
		
		final WalletPayTccMethodRequest request = new WalletPayTccMethodRequest();
		request.setPayAmount(1000l);
		request.setUserId(1);
		TransactionId parentTrxId = new TransactionId(applicationName, "concurrentTest", String.valueOf(i));
		HashMap<String,Object> header = new HashMap<>();
		header.put(EasytransConstant.CallHeadKeys.TANSACTION_ID_KEY, parentTrxId);
		header.put(EasytransConstant.CallHeadKeys.CALL_SEQ, 1);
		
		Callable<Object> tryRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "doTry", header, request);
			}
		};
		
		Callable<Object> cancelRequest = new Callable<Object>() {
			@Override
			public Object call() throws Exception {
				return consumer.call(annotation.appId(), annotation.busCode(), "doCancel",header, request);
			}
		};
		
		
		List<Callable<Object>> asListTry = Arrays.asList(tryRequest,tryRequest,tryRequest,tryRequest,tryRequest,tryRequest,tryRequest,tryRequest);
		try {
			List<Future<Object>> invokeAll = executor.invokeAll(asListTry);
			for(Future<Object> future:invokeAll){
				try {
					System.out.println(future.get());
				} catch (ExecutionException e) {
					LOG.info("Concurrent error message:" + e.getMessage());
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		List<Callable<Object>> asListCancel = Arrays.asList(cancelRequest,cancelRequest,cancelRequest,cancelRequest,cancelRequest,cancelRequest,cancelRequest,cancelRequest);
		try {
			List<Future<Object>> invokeAll = executor.invokeAll(asListCancel);
			for(Future<Object> future:invokeAll){
				try {
					System.out.println(future.get());
				} catch (ExecutionException e) {
					LOG.info("Concurrent error message:" + e.getMessage());
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
	}

	private void rollbackWithExceptionInMiddleOfConsistenGuardian() {
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_BEFORE_COMMIT);
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_IN_MIDDLE_OF_CONSISTENT_GUARDIAN_WITH_ROLLEDBACK_MASTER_TRANS);
			orderService.buySomething(1, 1000);
		} catch (Exception e) {
			LOG.info(e.getMessage());
		}
		
		try {
			Thread.sleep(1000);//wait for asynchronous operation
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		OrderService.clearExceptionSet();
		List<LogCollection> unfinishedLogs = logReader.getUnfinishedLogs(null, 1, new Date());
		LogCollection logCollection = unfinishedLogs.get(0);
		guardian.process(logCollection);
	}

	private void commitWithExceptionInMiddleOfConsistenGuardian() {
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_IN_MIDDLE_OF_CONSISTENT_GUARDIAN_WITH_SUCCESS_MASTER_TRANS);
			orderService.buySomething(1, 1000);
		} catch (Exception e) {
			LOG.info(e.getMessage());
		}
		
		try {
			Thread.sleep(1000);//wait for asynchronous operation
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		OrderService.clearExceptionSet();
		List<LogCollection> unfinishedLogs = logReader.getUnfinishedLogs(null, 1, new Date());
		LogCollection logCollection = unfinishedLogs.get(0);
		guardian.process(logCollection);
	}

	private void cleanAndSetUp() {
		wholeJdbcTemplate.batchUpdate(new String[]{
				"Create Table If Not Exists `order` (  `order_id` int(11) NOT NULL AUTO_INCREMENT,  `user_id` int(11) NOT NULL,  `money` bigint(20) NOT NULL,  `create_time` datetime NOT NULL,  PRIMARY KEY (`order_id`)) DEFAULT CHARSET=utf8",
				"TRUNCATE `order`",
				"Create Table If Not Exists `wallet` (  `user_id` int(11) NOT NULL,  `total_amount` bigint(20) NOT NULL,  `freeze_amount` bigint(20) NOT NULL,  PRIMARY KEY (`user_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8",
				"TRUNCATE `wallet`",
				"Create Table If Not Exists  `accounting` (  `accounting_id` int(11) NOT NULL AUTO_INCREMENT,  `p_app_id` varchar(32) NOT NULL,  `p_bus_code` varchar(128) NOT NULL,  `p_trx_id` varchar(64) NOT NULL,  `user_id` int(11) NOT NULL,  `amount` bigint(20) NOT NULL,  `create_time` datetime NOT NULL,  PRIMARY KEY (`accounting_id`)) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8",
				"TRUNCATE `accounting`",
				"Create Table If Not Exists `point` (  `user_id` int(11) NOT NULL,  `point` bigint(20) NOT NULL,  PRIMARY KEY (`user_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8",
				"TRUNCATE `point`",
				"Create Table If Not Exists `express` (  `p_app_id` varchar(32) NOT NULL,  `p_bus_code` varchar(128) NOT NULL,  `p_trx_id` varchar(64) NOT NULL,  `user_id` int(11) NOT NULL,  PRIMARY KEY (`p_app_id`,`p_bus_code`,`p_trx_id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8",
				"TRUNCATE `express`",
				"TRUNCATE `executed_trans`",
				"TRUNCATE `idempotent`",
				"INSERT INTO `wallet` (`user_id`, `total_amount`, `freeze_amount`) VALUES ('1', '10000', '0')",
				"INSERT INTO `point` (`user_id`, `point`) VALUES ('1', '0')"
		});
		
		JdbcTemplate transLogJdbcTemplate = new JdbcTemplate(dbForLog.getDataSource());
		transLogJdbcTemplate.batchUpdate(new String[]{
				"TRUNCATE `trans_log_unfinished`",
				"TRUNCATE `trans_log_detail`",
		});
		
	}

	public void commitedAndSubTransSuccess(){
		orderService.buySomething(1, 1000);
	}
	
	public void rollbackWithExceptionJustBeforeCommit(){
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_BEFORE_COMMIT);
			orderService.buySomething(1, 1000);
		} catch (UtProgramedException e) {
			LOG.info(e.getMessage());
		}
		OrderService.clearExceptionSet();
	}
	
	public void rollbackWithExceptionInMiddle(){
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_IN_THE_MIDDLE);
			orderService.buySomething(1, 1000);
		} catch (UtProgramedException e) {
			LOG.info(e.getMessage());
		}
		OrderService.clearExceptionSet();
	}
	
	public void rollbackWithExceptionJustAfterStartEasyTrans(){
		try {
			OrderService.setExceptionTag(OrderService.EXCEPTION_TAG_JUST_AFTER_START_EASY_TRANSACTION);
			orderService.buySomething(1, 1000);
		} catch (UtProgramedException e) {
			LOG.info(e.getMessage());
		}
		OrderService.clearExceptionSet();
	}
	
}
