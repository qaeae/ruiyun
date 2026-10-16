package com.wms.platform.common.device.simulated;

import com.wms.platform.common.device.dto.PrintResultDto;
import com.wms.platform.common.device.dto.PrintTaskDto;
import com.wms.platform.common.device.port.LabelPrinterPort;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模拟面单打印机（开发/测试/PoC 用，无需硬件）。
 *
 * <p>三个行为是刻意实现的，因为它们对应真实打印设备的三个坑：
 * <ol>
 *   <li><b>重复任务不重复出纸</b>：网络抖动导致任务重投时，真实打印机不会"记得"自己打过，
 *       必须靠 {@code taskId} 去重——现场后果是一个包裹两张面单；</li>
 *   <li><b>可模拟离线</b>：打印失败必须能被测试覆盖（业务侧要知道失败后是重试还是转人工）；</li>
 *   <li><b>记录已打印任务</b>：供断言"到底打了几张、打的哪张"。</li>
 * </ol>
 */
public class SimulatedLabelPrinter implements LabelPrinterPort {

    /** 已打印的任务 ID（去重键）。 */
    private final Set<String> printedTaskIds = ConcurrentHashMap.newKeySet();

    /** 已打印的任务明细（顺序即打印顺序）。 */
    private final ConcurrentLinkedQueue<PrintTaskDto> printedTasks = new ConcurrentLinkedQueue<>();

    /** 是否离线（模拟打印机断电/断网）。 */
    private final AtomicBoolean offline = new AtomicBoolean(false);

    @Override
    public PrintResultDto print(PrintTaskDto task) {
        // 1. 任务参数校验：缺任务 ID/打印机/份数非法时直接失败（不抛异常，交给业务处理）
        if (task == null || task.taskId() == null || task.taskId().isBlank()) {
            return PrintResultDto.failure(null, null, "打印任务缺少 taskId");
        }
        if (task.printerCode() == null || task.printerCode().isBlank()) {
            return PrintResultDto.failure(task.taskId(), null, "未指定打印机编码");
        }
        if (task.copies() < 1 || task.copies() > PrintTaskDto.MAX_COPIES) {
            return PrintResultDto.failure(task.taskId(), task.printerCode(),
                    "打印份数必须在 1~" + PrintTaskDto.MAX_COPIES + " 之间，实际 " + task.copies());
        }
        // 2. 模拟设备故障：离线时返回失败，业务侧据此重试或转人工
        if (offline.get()) {
            return PrintResultDto.failure(task.taskId(), task.printerCode(), "打印机离线（模拟）");
        }
        // 3. 幂等去重：同一 taskId 重复投递不再出纸，直接返回成功
        if (!printedTaskIds.add(task.taskId())) {
            return PrintResultDto.success(task.taskId(), task.printerCode());
        }
        // 4. 记录并返回成功
        printedTasks.add(task);
        return PrintResultDto.success(task.taskId(), task.printerCode());
    }

    /** 让打印机进入离线状态（测试失败分支用）。 */
    public void goOffline() {
        offline.set(true);
    }

    /** 让打印机恢复在线。 */
    public void goOnline() {
        offline.set(false);
    }

    /**
     * 已打印的任务明细（只读快照）。
     *
     * @return 按打印顺序排列的任务列表
     */
    public List<PrintTaskDto> printedTasks() {
        return List.copyOf(printedTasks);
    }

    /**
     * 已出纸张数（= 份数之和，用于断言"有没有重复出纸"）。
     *
     * @return 总张数
     */
    public int printedSheets() {
        return printedTasks.stream().mapToInt(PrintTaskDto::copies).sum();
    }

    /** 清空记录（用例之间隔离）。 */
    public void reset() {
        printedTaskIds.clear();
        printedTasks.clear();
        offline.set(false);
    }
}
