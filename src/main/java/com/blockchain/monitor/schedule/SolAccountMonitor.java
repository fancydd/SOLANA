package com.blockchain.monitor.schedule;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.blockchain.monitor.config.CustomerProperties;
import com.blockchain.monitor.dto.*;
import com.blockchain.monitor.utils.CommonUtil;
import com.blockchain.monitor.websocket.CustomerWebsocket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class SolAccountMonitor extends BaseAccountMonitor {

    @Autowired
    private CustomerProperties customerProperties;

    @Value("${spring.application.name}")
    private String coinType;

    public static CustomerWebsocket websocket = null;

    protected final int consumerCount = 8;

    protected final ExecutorService consumerThreadPool = Executors.newFixedThreadPool(consumerCount);

    protected final int todoCount = 3;

    protected final ExecutorService todoThreadPool = Executors.newFixedThreadPool(todoCount);

    protected final int catchCount = 3;

    protected final ExecutorService catchThreadPool = Executors.newFixedThreadPool(catchCount);

    protected final String SLOT_QUEUE = "sol:slot_queue";

    protected final String SLOT_TODO_QUEUE = "sol:todo_slot_queue";

    protected final String CATCHUP_QUEUE = "sol:catchup_queue";

    private final Timer timer = new Timer(true);

    @Override
    public long getBlockTime() {
        return 400;
    }

    private int lastUrlIndex = 0;

    public synchronized String getUrl(){
        if(lastUrlIndex >= customerProperties.getNode().getUrls().size()){
            lastUrlIndex = 0;
        }
        String url = customerProperties.getNode().getUrls().get(lastUrlIndex++);
        return url;
    }


    @Override
    public void startProducer() {
        AtomicLong latestSlot = new AtomicLong(getBlockHeight());

        startCatchup(latestSlot.get());

        AtomicLong incrCount = new AtomicLong();
        producerThreadPool.execute(() -> {
            while (true){
                // 0.4秒push一个块
                try {
                    Thread.sleep(getBlockTime());
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                while (true){
                    try {
                        redisTemplate.opsForList().leftPush(SLOT_QUEUE, latestSlot.getAndIncrement());
                        incrCount.incrementAndGet();
                        if(incrCount.incrementAndGet() >= 1000){
                            log.info("start check the queue");
                            long blockHeight = getBlockHeight();
                            if(blockHeight < latestSlot.getAndIncrement() && (latestSlot.getAndIncrement()-blockHeight) > 1000){
                                long left = latestSlot.getAndIncrement() - blockHeight;
                                long sleepTime = left * getBlockTime() / 2;
                                log.info("producer left {} start sleep {} ms",left,sleepTime);
                                Thread.sleep(sleepTime);
                            }
                            incrCount.set(0);
                        }
                        break;
                    }catch (Exception e){
                        log.error("递增块号异常，准备重试",e);
                    }
                }
            }
        });


        // 消费者线程
        for (int i = 0; i < consumerCount; i++) {
            consumerThreadPool.execute(() -> {
                while (true) {
                    long blockHeight;
                    while (true){
                        try {
                            Object obj = redisTemplate.opsForList().rightPop(SLOT_QUEUE,1, TimeUnit.HOURS);
                            blockHeight = Long.parseLong(obj.toString());
                            break;
                        }catch (Exception e){
                            log.error("获取最新块号异常",e);
                        }
                    }
                    handleBlock(blockHeight);
                    ThreadUtil.sleep(100);
                }

            });
            ThreadUtil.sleep(500);
        }

        // 补块线程
        for (int i = 0; i < todoCount; i++) {
            todoThreadPool.execute(() -> {
                while (true) {
                    long blockHeight;
                    while (true){
                        try {
                            Object obj = redisTemplate.opsForList().rightPop(SLOT_TODO_QUEUE,2, TimeUnit.MINUTES);
                            blockHeight = Long.parseLong(obj.toString());
                            break;
                        }catch (Exception e){
                            log.error("获取最新块号异常",e);
                        }
                    }
                    handleBlock(blockHeight);
                    ThreadUtil.sleep(100);
                }

            });
        }

    }

    // 开始追快
    public void startCatchup(Long currentHeight){
//        Long index = redisTemplate.opsForList().indexOf(SLOT_QUEUE, blockHeight);
//        List catchRange;
//        if(Objects.nonNull(index)){
//            catchRange = redisTemplate.opsForList().range(SLOT_QUEUE, index, -1);
//        }else{
//            catchRange = redisTemplate.opsForList().range(SLOT_QUEUE, 0, -1);
//        }
//        redisTemplate.opsForList().rightPushAll(CATCHUP_QUEUE,catchRange);
//        redisTemplate.delete(SLOT_QUEUE);
        while (true){
            try {
                // Lua 脚本
                String luaScript = """
                    local slot_queue = KEYS[1]
                    local catchup_queue = KEYS[2]
                    local block_height = ARGV[1]
                    local index = redis.call('LPOS', slot_queue, block_height)
                    local catch_range
                    if index then
                        catch_range = redis.call('LRANGE', slot_queue, index, -1)
                    else
                        catch_range = redis.call('LRANGE', slot_queue, 0, -1)
                    end
                    for i, value in ipairs(catch_range) do
                        redis.call('RPUSH', catchup_queue, value)
                    end
                    redis.call('DEL', slot_queue)
                    return catch_range
                    """;

                // 创建 RedisScript 对象
                DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
                redisScript.setScriptText(luaScript);
                redisScript.setResultType(List.class);

                // 执行 Lua 脚本
                redisTemplate.execute(redisScript, Arrays.asList(SLOT_QUEUE, CATCHUP_QUEUE), currentHeight);
                break;
            }catch (Exception e){
                log.error("startCatchup error",e);
            }
        }


        List range = null;
        while (true){
            try {
                range = redisTemplate.opsForList().range(CATCHUP_QUEUE, 0, -1);
                break;
            }catch (Exception e){
                log.error("startCatchup range catchup_queue error",e);
            }
        }

        if(CollUtil.isNotEmpty(range)){
            int size = range.size();
            List finalRange = range;
            AtomicInteger ac = new AtomicInteger();
            for (int i = 0; i < catchCount; i++) {
                catchThreadPool.execute(() -> {
                    int index;
                    while ((index = ac.getAndIncrement()) < size) {
                        try {
                            Object obj = finalRange.get(index);
                            if(Objects.nonNull(obj)){
                                Long blockHeight = Long.parseLong(obj.toString());
                                handleBlock(blockHeight);
                                redisTemplate.opsForList().remove(CATCHUP_QUEUE,1,blockHeight);
                            }
                        }catch (Exception e){
                            log.error("catchup handleBlock Error",e);
                        }
                    }
                });
            }
        }

    }

    public JSONObject getBlockForRpc(Long block){
        JSONObject jsonObject = CommonUtil.sendJsonRpc(getUrl(), String.format("""
                  {
                    "jsonrpc": "2.0","id":1,
                    "method":"getBlock",
                    "params": [
                      %s,
                      {
                        "encoding": "json",
                        "maxSupportedTransactionVersion":0,
                        "transactionDetails":"full",
                        "rewards":false
                      }
                    ]
                  }
                """, block));
        return jsonObject;
    }

    @Override
    public List<CallbackData> parseTransaction(Long blockNumber) {
        List<CallbackData> list = new ArrayList<>();
        JSONObject blockForRpc = getBlockForRpc(blockNumber);
        if(Objects.isNull(blockForRpc)){
            log.error("blockNumber is null {}",blockNumber);
            return list;
        }
        if(!blockForRpc.containsKey("error")){
            JSONObject result = blockForRpc.getJSONObject("result");
            if(Objects.isNull(result)){
                // 大概率是api出问题了，等0.4秒重试
                try {
                    Thread.sleep(getBlockTime());
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return parseTransaction(blockNumber);
            }
            list =  handleBlock(result);
        }else if(blockForRpc.getJSONObject("error").getStr("message").contains("Block not available for slot")){
            // 如果当前块号不存在就等1秒重试
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return parseTransaction(blockNumber);
        }else if(blockForRpc.getJSONObject("error").getStr("message").contains("was skipped")){
            String message = blockForRpc.getJSONObject("error").getStr("message");
            log.error("block {} is error : {}",blockNumber,message);
            /**
             * "message": "Slot 276916903 was skipped, or missing in long-term storage"
             * 这种提示就意味着块号被跳过
             */
            if(!message.contains("missing in long-term storage")){
                // 块号有问题等待后放入对列
                pushTodoQueue(blockNumber);
            }
        }else if(429 == blockForRpc.getJSONObject("error").getInt("code")){
//            try {
//                Thread.sleep(1000);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
            log.warn("block {} is 429",blockNumber);
            try {
                // 限流，等0.1秒重试
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return parseTransaction(blockNumber);
        }else if (blockForRpc.getJSONObject("error").getStr("message").contains("Monthly capacity limit exceeded")){
            log.warn("block error {}",blockForRpc);
            try {
                // 限流，等0.1秒重试
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return parseTransaction(blockNumber);
        }else{
            log.error("block {} is error {}",blockNumber,blockForRpc);
        }
        return list;
    }

    /**
     * 一分钟后放置对列
     * @param blockNumber
     */
    private void pushTodoQueue(Long blockNumber) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                while (true){
                    try {
                        redisTemplate.opsForList().leftPush(SLOT_TODO_QUEUE,blockNumber);
                        break;
                    }catch (Exception e){
                        log.error("【pushTodoQueue】",e);
                    }
                }
            }
        },1000 * 60);

    }


    public List<CallbackData> handleBlock(JSONObject block){
        List<CallbackData> list = new CopyOnWriteArrayList<>();
        Long timestamp = block.getLong("blockTime");
        String blockHash = block.getStr("blockhash");
        long blockNumber = block.getLong("parentSlot") + 1;
//        log.info("handleBlock：{}",blockNumber);
        List<JSONObject> transactions = block.getJSONArray("transactions").toList(JSONObject.class);
        transactions.parallelStream().forEach(trans -> {
            try {
                JSONObject meta = trans.getJSONObject("meta");
                JSONObject status = meta.getJSONObject("status");
                if (status.containsKey("Err")) {
                    return;
                }
                JSONArray innerInstructions = meta.getJSONArray("innerInstructions");
                BigDecimal fee = meta.getBigDecimal("fee");
                JSONObject transaction = trans.getJSONObject("transaction");
                String txID = (String) transaction.getJSONArray("signatures").get(0);

                List<JSONObject> preTokenBalances = meta.getJSONArray("preTokenBalances").toList(JSONObject.class);
                List<JSONObject> postTokenBalances = meta.getJSONArray("postTokenBalances").toList(JSONObject.class);
                List<JSONObject> accountKeys = new ArrayList<>();
                List<String> messageAccountKeys = new ArrayList<>();
                if(CollUtil.isNotEmpty(transaction.getJSONArray("accountKeys"))){
                    accountKeys = transaction.getJSONArray("accountKeys").toList(JSONObject.class);
                }else if(CollUtil.isNotEmpty(transaction.getJSONObject("message").getJSONArray("accountKeys"))){
                    messageAccountKeys = transaction.getJSONObject("message").getJSONArray("accountKeys").toList(String.class);
                }
                List<BigDecimal> preBalances = meta.getJSONArray("preBalances").toList(BigDecimal.class);
                List<BigDecimal> postBalances = meta.getJSONArray("postBalances").toList(BigDecimal.class);

                if (CollUtil.isNotEmpty(postTokenBalances)
                        && CollUtil.isNotEmpty(preTokenBalances)
                        && postTokenBalances.size() == 2) {
                    // 处理代币交易
                    String from = "";
                    String to = "";
                    BigDecimal preAmount;
                    BigDecimal postAmount;
                    BigDecimal amount;
                    String mint;
                    // 如果是pretokenbalance长度为1意味着创建了一个新账户
                    if(preTokenBalances.size() == 1){
                        JSONObject pre = preTokenBalances.get(0);
                        mint = pre.getStr("mint");
                        int toIndex = postTokenBalances.get(0).getStr("owner").equalsIgnoreCase(pre.getStr("owner")) ? 1 : 0;
                        int fromIndex = postTokenBalances.get(0).getStr("owner").equalsIgnoreCase(pre.getStr("owner")) ? 0 : 1;
                        JSONObject toJsonObj = postTokenBalances.get(toIndex);
                        JSONObject fromJsonobj = postTokenBalances.get(fromIndex);

//                        String owner = toJsonObj.getStr("owner");
//                        Integer accountIndex = toJsonObj.getInt("accountIndex");
//                        String pubkey = "";
//                        if(CollUtil.isNotEmpty(accountKeys)){
//                            if(accountIndex >= accountKeys.size()){
//                                List<JSONObject> addressTableLookups = trans.getByPath("transaction.message.addressTableLookups", List.class);
//                                JSONObject tableLookup = addressTableLookups.get(0);
//                                String accountKey = tableLookup.getStr("accountKey");
//                                List<String> accountInfo = getAccountInfo(accountKey);
//                                if(CollUtil.isNotEmpty(accountInfo)){
//                                    pubkey = accountInfo.get(accountInfo.size() - (accountIndex + accountKeys.size()));
//                                }
//                            }else{
//                                JSONObject account = accountKeys.get(accountIndex);
//                                pubkey = account.getStr("pubkey");
//                            }
//                        }else{
//                            if(accountIndex >= messageAccountKeys.size()){
//                                List<JSONObject> addressTableLookups = trans.getByPath("transaction.message.addressTableLookups", List.class);
//                                JSONObject tableLookup = addressTableLookups.get(0);
//                                String accountKey = tableLookup.getStr("accountKey");
//                                List<String> accountInfo = getAccountInfo(accountKey);
//                                if(CollUtil.isNotEmpty(accountInfo)){
//                                    pubkey = accountInfo.get(accountInfo.size() - (accountIndex + messageAccountKeys.size()));
//                                }
//                            }else{
//                                pubkey = messageAccountKeys.get(accountIndex);
//                            }
//                        }

                        from = fromJsonobj.getStr("owner");
                        to = toJsonObj.getStr("owner");

                        amount = toJsonObj.getJSONObject("uiTokenAmount").getBigDecimal("uiAmountString", BigDecimal.ZERO);
                    }else{
                        JSONObject post = postTokenBalances.get(0);
                        JSONObject pre = preTokenBalances.get(0);

                        postAmount = post.getJSONObject("uiTokenAmount").getBigDecimal("uiAmountString", BigDecimal.ZERO);
                        preAmount = pre.getJSONObject("uiTokenAmount").getBigDecimal("uiAmountString", BigDecimal.ZERO);
                        amount = postAmount.subtract(preAmount);
                        if(amount.compareTo(BigDecimal.ZERO) > 0){
                            from = JSONUtil.parseObj(postTokenBalances.get(1)).getStr("owner");
                            to = post.getStr("owner");
                        }else{
                            from = post.getStr("owner");
                            to = JSONUtil.parseObj(postTokenBalances.get(1)).getStr("owner");
                        }
                        mint = post.getStr("mint");
//                            Integer accountIndex = post.getInt("accountIndex");
                    }

                    list.add(buildCallbackData(blockHash, amount.abs(), txID, mint, blockNumber, from, to, from, timestamp,0));
                    list.add(buildCallbackData(blockHash, amount.abs(), txID, mint, blockNumber, from, to, to, timestamp,0));

                } else if (CollUtil.isNotEmpty(postBalances) && CollUtil.isEmpty(innerInstructions)) {
                    // 处理主币交易
                    String from = "";
                    String to = "";
                    BigDecimal amount = BigDecimal.ZERO;
                    if(CollUtil.isNotEmpty(accountKeys)){
                        for (int i = 0; i < accountKeys.size(); i++) {
                            JSONObject account = accountKeys.get(i);
                            Boolean writable = account.getBool("writable");
                            if (!writable) {
                                continue;
                            }

                            String pubkey = account.getStr("pubkey");
                            BigDecimal pre = preBalances.get(i);
                            BigDecimal post = postBalances.get(i);
                            Boolean signer = account.getBool("signer");
                            if (signer) {
                                from = pubkey;
                                amount = pre.subtract(post).subtract(fee).movePointLeft(9).abs();
                            } else {
                                to = pubkey;
                            }
                        }
                    }else{
                        for (int i = 0; i < messageAccountKeys.size(); i++) {
                            BigDecimal tempAmount = BigDecimal.ZERO;
                            String account = messageAccountKeys.get(i);
                            BigDecimal pre = preBalances.get(i);
                            BigDecimal post = postBalances.get(i);
                            tempAmount = pre.subtract(post);
                            if(tempAmount.compareTo(BigDecimal.ZERO) > 0){
                                from = account;
                                amount = tempAmount.subtract(fee).movePointLeft(9).abs();
                            }
                            if(tempAmount.compareTo(BigDecimal.ZERO) < 0){
                                to = account;
                            }
                        }
                    }
                    list.add(buildCallbackData(blockHash, amount, txID, "", blockNumber, from, to, from, timestamp,0));
                    list.add(buildCallbackData(blockHash, amount, txID, "", blockNumber, from, to, to, timestamp,0));
                }else {
                    // 处理合约
                    String from = "";
                    List<String> accounts = new ArrayList<>();
                    Integer fromIndex = trans.getByPath("transaction.message.header.numReadonlySignedAccounts",Integer.class);
                    Integer contractIndex = trans.getByPath("transaction.message.header.numReadonlyUnsignedAccounts",Integer.class);
                    if(CollUtil.isNotEmpty(accountKeys)){
                        from = accountKeys.get(fromIndex).getStr("pubkey");
                        accounts = accountKeys.stream().map(item -> item.getStr("pubkey")).toList();
                    }else{
                        from = messageAccountKeys.get(fromIndex);
                        accounts = new ArrayList<>(messageAccountKeys);
                    }

                    String contract = accounts.get(contractIndex);

                    accounts.remove(from);
                    String to = JSONUtil.toJsonStr(accounts);
                    list.add(buildCallbackData(blockHash, BigDecimal.ZERO, txID, contract, blockNumber, from, to, from, timestamp,1));
                    for (String toAddr : accounts) {
                        list.add(buildCallbackData(blockHash, BigDecimal.ZERO, txID, contract, blockNumber, from, to, toAddr, timestamp,1));
                    }
                }
            } catch (Exception e) {
                log.error("【解析交易失败】trans:{}", trans, e);
                discordBotService.sendErrMsg("【" + coinType + "】" + "【解析交易失败】" + trans.toStringPretty() + e.getMessage());
                throw e;
            }
        });


        return list;
    }

    public CallbackData buildCallbackData(String blockHash, BigDecimal amount, String txID, String contractAddress, long blockNum, String from, String to, String addr, long timestamp,int type) {
        CallbackData build = CallbackData.builder()
                .monitorAddr(addr)
                .coinType(coinType)
                .json(JSONUtil.createObj()
                        .set("coinType", coinType)
                        .set("blockHash", blockHash)
                        .set("amount", amount.stripTrailingZeros().toPlainString())
                        .set("txID", txID)
                        .set("contractAddress", contractAddress)
                        .set("blockNum", blockNum)
                        .set("from", from)
                        .set("to", to)
                        .set("addr", addr)
                        .set("type",type)
                        .set("timestamp", timestamp).toString()
                ).build();
        return build;
    }

    public List<String> getAccountInfo(String addr){
        JSONObject jsonObject = CommonUtil.sendJsonRpc(getUrl(), String.format("""
                {
                   "jsonrpc": "2.0",
                   "id": 1,
                   "method": "getAccountInfo",
                   "params": [
                     "%s",
                     {
                       "encoding": "jsonParsed"
                     }
                   ]
                 }""", addr));

        JSONObject result = jsonObject.getJSONObject("result");
        if(Objects.nonNull(result.getJSONObject("value"))){
            List<String> list = result.getByPath("value.data.parsed.info.addresses", List.class);
            return  list;
        }
        return new ArrayList<>();
    }

    @Override
    public long getBlockHeight() {
//        while (true){
//            try {
//                String response = HttpRequest.get("https://api-v2.solscan.io/v2/block/last?q=1")
//                        .header("origin", "https://solscan.io")
//                        .execute().body();
//                JSONObject jsonObject = JSONUtil.parseObj(response);
//                List<JSONObject> data = jsonObject.getJSONArray("data").toList(JSONObject.class);
//                return data.get(0).getLong("currentSlot");
//            }catch (Exception e){
//                log.error("【getBlockHeight】",e);
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException ex) {
//                    throw new RuntimeException(ex);
//                }
//            }
//        }
        while (true){
            try {
                JSONObject jsonObject = CommonUtil.sendJsonRpc(getUrl(), """
                            {"jsonrpc":"2.0","id":1, "method":"getSlot"}
                        """);

                return jsonObject.getLong("result");
            }catch (Exception e){
                log.error("【getLatestBlock】",e);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    @Override
    public List<SearchBalanceRes> getAddressBalance(List<SearchBalanceReq> req) {
        List<SearchBalanceRes> res = new ArrayList<>();
        for (SearchBalanceReq searchBalanceReq : req) {
            SearchBalanceRes searchBalanceRes = new SearchBalanceRes();
            searchBalanceRes.setAddress(searchBalanceReq.getAddress());
            searchBalanceRes.setCoinType(coinType);
            try {
                while (true){
                    JSONObject response = CommonUtil.sendJsonRpc(getUrl(), String.format("""
                              {
                                "jsonrpc": "2.0", "id": 1,
                                "method": "getBalance",
                                "params": [
                                  "%s",
                                  {
                                    "commitment": "confirmed"
                                  }
                                ]
                              }
                            """,searchBalanceReq.getAddress()));
                    if(response.containsKey("error")){
                        boolean contains = response.getStr("error").contains("error selecting node");
                        if(contains){
                            continue;
                        }
                        throw new RuntimeException(response.toJSONString(0));
                    }
                    BigDecimal value = response.getByPath("result.value",BigDecimal.class);
                    searchBalanceRes.setBalance(value.movePointLeft(9));
                    break;
                }
            }catch (Exception e){
                log.error("【获取余额异常】",e);
                throw e;
            }
            res.add(searchBalanceRes);
        }

        return res;
    }

    @Override
    public List<SearchTokenBalanceRes> getAddressTokenBalance(List<SearchTokenBalanceReq> req) {
        List<SearchTokenBalanceRes> res = new ArrayList<>();
        for (SearchTokenBalanceReq searchBalanceReq : req) {
            SearchTokenBalanceRes searchBalanceRes = new SearchTokenBalanceRes();
            searchBalanceRes.setAddress(searchBalanceReq.getAddress());
            searchBalanceRes.setCoinType(coinType);
            searchBalanceRes.setContractAddr(searchBalanceReq.getContractAddress());

            try {
                while (true){

                    JSONObject response = CommonUtil.sendJsonRpc(getUrl(), String.format("""
                      {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "getTokenAccountsByOwner",
                        "params": [
                          "%s",
                          {
                            "mint": "%s"
                          },
                          {
                            "commitment": "confirmed",
                            "encoding": "jsonParsed"
                          }
                        ]
                      }
                    """, searchBalanceReq.getAddress(), searchBalanceReq.getContractAddress()));

                    if(response.containsKey("error")){
                        boolean contains = response.getStr("error").contains("error selecting node");
                        if(contains){
                            continue;
                        }
                        throw new RuntimeException(response.toJSONString(0));
                    }

                    List<JSONObject> list = response.getByPath("result.value",List.class);
                    JSONObject jsonObject = list.get(0);
                    JSONObject tokenAmount = jsonObject.getByPath("account.data.parsed.info.tokenAmount",JSONObject.class);
                    BigDecimal uiAmount = tokenAmount.getBigDecimal("uiAmount",BigDecimal.ZERO);
                    searchBalanceRes.setBalance(uiAmount);
                    break;
                }
            }catch (Exception e){
                log.error("【获取代币余额异常】",e);
                throw e;
            }
            res.add(searchBalanceRes);
        }
        return res;
    }

}
