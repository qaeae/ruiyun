package com.wms.platform.modules.logistics.api;

import com.wms.platform.common.api.ApiResponse;
import com.wms.platform.common.security.PermissionCodes;
import com.wms.platform.common.security.RequiresPermission;
import com.wms.platform.modules.logistics.app.ShipmentProofService;
import com.wms.platform.modules.logistics.api.vo.ShipmentProofVo;
import com.wms.platform.modules.logistics.domain.ProofBizType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 发货单影像接口（P5）：回单/异常照片/面单存档的**上传、列表、下载**。
 *
 * <p><b>为什么要单独的应用内下载接口</b>：本地存储模式下没有预签名 URL；
 * 而且**权限必须由应用判定**——如果只给预签名 URL，URL 一旦泄露，在有效期内任何人可下载
 * （对象存储层不认识我们的用户）。因此：
 * <ul>
 *   <li>MinIO 模式：列表返回预签名 URL（前端直连，省应用带宽），有效期短（默认 30 分钟）；</li>
 *   <li>两种模式都提供 {@code GET .../content} 应用内下载（走鉴权，适合敏感影像）。</li>
 * </ul>
 */
@Validated
@RestController
@RequestMapping("/api/v1/logistics/shipments/{shipmentNo}/proofs")
@RequiredArgsConstructor
public class ShipmentProofController {

    /** 影像服务。 */
    private final ShipmentProofService proofService;

    /**
     * 上传影像（multipart）。
     *
     * @param shipmentNo  发货单号
     * @param bizType     用途（POD/EXCEPTION/LABEL/OTHER）
     * @param file        文件
     * @param remark      备注
     * @return 影像视图
     * @throws IOException 读取失败
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(PermissionCodes.LOGISTICS_PROOF_UPLOAD)
    public ApiResponse<ShipmentProofVo> upload(@PathVariable String shipmentNo,
                                               @RequestParam(defaultValue = "POD") String bizType,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) String remark) throws IOException {
        return ApiResponse.ok(ShipmentProofVo.of(proofService.upload(shipmentNo, ProofBizType.of(bizType),
                file.getOriginalFilename(), file.getContentType(), file.getSize(),
                file.getInputStream(), remark)));
    }

    /**
     * 查询影像列表。
     *
     * @param shipmentNo 发货单号
     * @param bizType    用途（可空）
     * @return 影像列表
     */
    @GetMapping
    @RequiresPermission(PermissionCodes.LOGISTICS_PROOF_QUERY)
    public ApiResponse<List<ShipmentProofVo>> list(@PathVariable String shipmentNo,
                                                   @RequestParam(required = false) String bizType) {
        return ApiResponse.ok(proofService.list(shipmentNo,
                        bizType == null || bizType.isBlank() ? null : ProofBizType.of(bizType)).stream()
                .map(ShipmentProofVo::of).toList());
    }

    /**
     * 应用内下载影像内容（走鉴权，适合敏感影像）。
     *
     * @param shipmentNo 发货单号
     * @param id         影像 ID
     * @return 文件流（含 Content-Disposition 与内容哈希头）
     */
    @GetMapping("/{id}/content")
    @RequiresPermission(PermissionCodes.LOGISTICS_PROOF_QUERY)
    public ResponseEntity<InputStreamResource> download(@PathVariable String shipmentNo,
                                                        @PathVariable long id) {
        ShipmentProofService.ProofDownload download = proofService.download(shipmentNo, id);
        String encodedName = URLEncoder.encode(download.fileName() == null ? "proof" : download.fileName(),
                StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                // 把内容哈希回给调用方：可用于校验下载到的影像与登记时一致
                .header("X-Content-SHA256", download.sha256())
                .contentType(MediaType.parseMediaType(
                        download.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : download.contentType()))
                .contentLength(download.sizeBytes())
                .body(new InputStreamResource(download.content()));
    }
}
