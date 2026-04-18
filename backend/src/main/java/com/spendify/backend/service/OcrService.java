package com.spendify.backend.service;

import com.spendify.backend.dto.OcrResponse;
import com.spendify.backend.entity.OcrProcessingLog;
import com.spendify.backend.repository.OcrProcessingLogRepository;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ONNX Runtime
import ai.onnxruntime.*;

// OpenCV
import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

// 文件操作
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * OCR service that uses Tesseract for receipt text extraction
 * and applies rule-based post-processing to extract entities.
 *
 * Note: A new Tesseract instance is created per request because
 * Tesseract's native code is not thread-safe.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final int MAX_IMAGE_DIMENSION = 8000;

    // Regex patterns from Python code
    private static final Pattern DATE_PATTERN_1 = Pattern.compile("\\d{4}[-/]\\d{2}[-/]\\d{2}");
    private static final Pattern DATE_PATTERN_2 = Pattern.compile("\\d{2}[-/]\\d{2}[-/]\\d{2,4}");
    private static final Pattern TOTAL_PATTERN = Pattern.compile("[\\$€£¥]?\\s*\\d{1,3}(,\\d{3})*(\\.\\d{2})");
    private static final Pattern UNWANTED_COMPANY_PATTERN = Pattern.compile("^(?!.*(RECEIPT|INVOICE|TAX|SUBTOTAL)).*$");

    // CTPN 配置参数（与 Python 代码一致）
    private static final int[] ANCHOR_HEIGHTS = {11, 15, 22, 32, 45, 65, 93, 133, 190, 273};
    private static final int FEAT_STRIDE = 16;
    private static final int ANCHOR_SHIFT = 16;
    private static final float TEXT_PROPOSAL_MIN_SCORE = 0.9f;
    private static final float NMS_IOU_THRESHOLD = 0.3f;
    private static final float MIN_V_OVERLAP = 0.7f;
    private static final float MIN_SIZE_SIM = 0.8f;
    private static final int MAX_HORIZONTAL_GAP = 50;

    // CharLM 词汇表
    private static final String CHARLM_VOCAB = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final OcrProcessingLogRepository ocrProcessingLogRepository;

    // ONNX Sessions
    private OrtEnvironment ortEnv;
    private OrtSession ctpnSession;
    private OrtSession charlmSession;
    private boolean ctpnAvailable = false;

    @Autowired
    public OcrService(OcrProcessingLogRepository ocrProcessingLogRepository) {
        this.ocrProcessingLogRepository = ocrProcessingLogRepository;
        // Initialization moved to @PostConstruct to ensure @Value injection is complete
    }

    @Value("${tesseract.data.path:tessdata}")
    private String tesseractDataPath;

    @Value("${tesseract.language:eng+chi_sim}")
    private String tesseractLanguage;

    @Value("${onnx.ctpn.model.path:onnx/ctpn.onnx}")
    private String ctpnModelPath;

    @Value("${onnx.charlm.model.path:onnx/charlm.onnx}")
    private String charlmModelPath;

    @Value("${onnx.enabled:true}")
    private boolean onnxEnabled;

    /**
     * Initialize ONNX Runtime environment after dependency injection is complete
     */
    @PostConstruct
    private void initializeOnnxRuntime() {
        if (!onnxEnabled) {
            log.info("ONNX pipeline 已禁用，将使用传统 Tesseract 方法");
            return;
        }

        try {
            // 尝试加载 OpenCV native library
            boolean opencvLoaded = false;
            try {
                nu.pattern.OpenCV.loadLocally();
                log.info("OpenCV 已初始化");
                opencvLoaded = true;
            } catch (UnsatisfiedLinkError | Exception e) {
                log.warn("OpenCV native library 加载失败: {}", e.getMessage());
                // 继续尝试其他方法
            }

            // 方法 2: 直接加载 /usr/lib/jni/libopencv_java*.so
            if (!opencvLoaded) {
                try {
                    Process process = Runtime.getRuntime().exec("find /usr/lib/jni -name 'libopencv_java*.so' 2>/dev/null");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String libPath = reader.readLine();
                    if (libPath != null) {
                        System.load(libPath);
                        log.info("✅ OpenCV System.load() 成功：{}", libPath);
                        opencvLoaded = true;
                    }
                    reader.close();
                    process.destroy();
                } catch (Exception e) {
                    log.debug("OpenCV System.load() 失败：{}", e.getMessage());
                }
            }
            
            // 方法 3: 最后尝试 System.loadLibrary
            if (!opencvLoaded) {
                try {
                    System.loadLibrary("opencv_java");
                    log.info("✅ OpenCV System.loadLibrary() 成功");
                    opencvLoaded = true;
                } catch (UnsatisfiedLinkError e) {
                    log.debug("OpenCV System.loadLibrary() 失败：{}", e.getMessage());
                }
            }

            // 如果所有 OpenCV 加载方法都失败
            if (!opencvLoaded) {
                log.warn("⚠️ OpenCV native library 加载失败，CTPN 文本检测将被禁用");
                ctpnAvailable = false;
            } else {
                ctpnAvailable = true;
                log.info("✅ OpenCV 已成功初始化");
            }

            // 初始化 ONNX Runtime
            ortEnv = OrtEnvironment.getEnvironment();
            log.info("ONNX Runtime environment initialized");

            // 只有在 OpenCV 可用时才加载 CTPN 模型
            if (ctpnAvailable) {
                try {
                    ctpnSession = loadModel(ctpnModelPath, "CTPN");
                    log.info("✅ CTPN 模型已加载：{}", ctpnModelPath);
                } catch (Exception e) {
                    log.warn("CTPN 模型加载失败：{}, CTPN 将被禁用", e.getMessage());
                    ctpnSession = null;
                    ctpnAvailable = false;
                }
            } else {
                ctpnSession = null;
                log.info("CTPN 模型跳过加载（OpenCV 不可用）");
            }

            // CharLM 不依赖 OpenCV，总是可以加载
            try {
                charlmSession = loadModel(charlmModelPath, "CharLM");
                log.info("✅ CharLM 模型已加载：{}", charlmModelPath);
            } catch (Exception e) {
                log.warn("CharLM 模型加载失败：{}, CharLM 将被禁用", e.getMessage());
                charlmSession = null;
            }

            log.info("========================================");
            log.info("ONNX pipeline 初始化完成");
            log.info("  - CTPN (文本检测): {}", ctpnAvailable ? "✅ 可用" : "❌ 不可用");
            log.info("  - CharLM (字符识别): {}", charlmSession != null ? "✅ 可用" : "❌ 不可用");
            log.info("========================================");

        } catch (Exception e) {
            log.error("❌ ONNX pipeline 初始化失败：{}", e.getMessage(), e);
            log.warn("将回退到传统 Tesseract 方法");
            onnxEnabled = false;
            ctpnAvailable = false;
            charlmSession = null;
        }
    }

    /**
     * 从 classpath 加载 ONNX 模型
     */
    private OrtSession loadModel(String modelPath, String modelName) throws Exception {
        // 从 classpath 加载模型
        InputStream is = getClass().getClassLoader().getResourceAsStream(modelPath);
        if (is == null) {
            throw new FileNotFoundException("找不到模型文件：" + modelPath);
        }

        // 复制到临时文件
        Path tempModel = Files.createTempFile(modelName.toLowerCase(), ".onnx");
        Files.copy(is, tempModel, StandardCopyOption.REPLACE_EXISTING);
        is.close();

        // 创建 ONNX Session
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        OrtSession session = ortEnv.createSession(tempModel.toString(), options);

        // 打印模型信息
        log.info("  模型：{}", modelName);
        log.info("  输入：{}", session.getInputNames());
        log.info("  输出：{}", session.getOutputNames());

        // 删除临时文件
        Files.delete(tempModel);

        return session;
    }


    /**
     * Process a receipt image and extract structured data.
     * Pipeline: Image -> Crop Preprocessing -> CTPN -> Tesseract -> Text Post-processing -> CharLM -> Entity Matching -> Output
     */
    public OcrResponse processReceipt(MultipartFile file) {
        long startTime = System.currentTimeMillis();
        OcrProcessingLog ocrLog = new OcrProcessingLog();
        ocrLog.setInputImageName(file.getOriginalFilename());

        try {
            validateImage(file);

            // Read and validate the image
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new IllegalArgumentException("Failed to read image file: unsupported or corrupted format");
            }
            if (image.getWidth() == 0 || image.getHeight() == 0) {
                throw new IllegalArgumentException("Invalid image dimensions: 0x0");
            }

            // Reject excessively large images
            if (image.getWidth() > MAX_IMAGE_DIMENSION || image.getHeight() > MAX_IMAGE_DIMENSION) {
                throw new IllegalArgumentException(
                        String.format("Image dimensions (%dx%d) exceed the maximum allowed limit of %dx%d.",
                                image.getWidth(), image.getHeight(), MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
                );
            }

            log.info("Processing image: {}x{} pixels, type: {}", image.getWidth(), image.getHeight(), image.getType());

            // 尝试使用 ONNX pipeline
            if (onnxEnabled) {
                try {
                    log.info("使用 ONNX pipeline 进行 OCR 处理...");
                    return processWithOnnxPipeline(file, image, ocrLog, startTime);
                } catch (Exception onnxException) {
                    log.error("ONNX pipeline 失败，回退到传统方法：{}", onnxException.getMessage(), onnxException);
                    // 继续执行传统方法
                }
            }

            // Fallback to traditional Tesseract method
            log.info("使用传统 Tesseract 方法进行 OCR 处理...");
            return processWithTraditionalMethod(file, image, ocrLog, startTime);

        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            ocrLog.setProcessingTimeMs(endTime - startTime);
            ocrLog.setSuccessful(false);
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.length() > 500) {
                errorMessage = errorMessage.substring(0, 500) + "...";
            }
            ocrLog.setErrorMessage(errorMessage);
            ocrProcessingLogRepository.save(ocrLog);
            log.error("OCR processing failed", e);
            throw new RuntimeException("OCR processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 ONNX pipeline 处理 OCR（主要方法）
     * Pipeline: Image -> Crop Preprocessing -> CTPN -> Tesseract -> Text Post-processing -> CharLM -> Entity Matching
     */
    private OcrResponse processWithOnnxPipeline(MultipartFile file, BufferedImage originalImage, 
                                                  OcrProcessingLog ocrLog, long startTime) throws Exception {
        
        BufferedImage processedImage = originalImage;
        
        // Step 1: Crop Preprocessing（裁剪预处理）- 仅在 CTPN 可用时执行
        if (ctpnAvailable) {
            try {
                processedImage = cropPreprocessing(originalImage);
                log.info("裁剪预处理完成：{}x{} pixels", processedImage.getWidth(), processedImage.getHeight());
            } catch (Exception e) {
                log.warn("裁剪预处理失败，使用原始图像: {}", e.getMessage());
                processedImage = originalImage;
            }
        }

        List<String> textLines;
        
        // Step 2: CTPN 文本检测 或 全图 OCR
        if (ctpnAvailable) {
            try {
                List<TextBox> textProposals = detectTextWithCTPN(processedImage);
                log.info("CTPN 检测到 {} 个文本框", textProposals.size());

                // Step 3: 将文本框转换为图像区域用于 Tesseract
                List<java.awt.Rectangle> textRegions = convertTextBoxesToRegions(textProposals);

                // Step 4: Tesseract OCR 引擎
                String extractedText = runTesseractOnRegions(processedImage, textRegions);
                log.info("Tesseract 提取了 {} 个字符", extractedText.length());
                ocrLog.setDetectedText(extractedText);

                // Step 5: 文本后处理
                textLines = postprocessText(extractedText);
            } catch (Exception e) {
                log.warn("CTPN 处理失败，回退到全图 OCR: {}", e.getMessage());
                String extractedText = runFullImageOCR(processedImage);
                ocrLog.setDetectedText(extractedText);
                textLines = Arrays.asList(extractedText.split("\n"));
            }
        } else {
            // CTPN 不可用，直接使用全图 OCR
            log.info("CTPN 不可用，使用全图 OCR");
            String extractedText = runFullImageOCR(processedImage);
            ocrLog.setDetectedText(extractedText);
            textLines = Arrays.asList(extractedText.split("\n"));
        }

        log.info("文本后处理完成，共 {} 行", textLines.size());

        // Step 6: CharLM 信息提取（CharLM 不依赖 OpenCV，通常可用）
        Map<String, String> entities = new HashMap<>();
        if (charlmSession != null) {
            try {
                entities = extractEntitiesWithCharLM(textLines);
                log.info("CharLM 提取实体：{}", entities);
            } catch (Exception e) {
                log.warn("CharLM 提取失败，使用基于规则的方法: {}", e.getMessage());
            }
        } else {
            log.info("CharLM 不可用，使用基于规则的方法");
        }

        // Step 7: 后处理和实体匹配
        String company = entities.getOrDefault("company", "");
        String dateStr = entities.getOrDefault("date", "");
        String amountStr = entities.getOrDefault("total", "");

        LocalDate date = parseDate(dateStr);
        BigDecimal amount = parseAmount(amountStr);

        // 如果 CharLM 未能提取某些实体，使用基于规则的后备方法
        if (company.isEmpty() || date == null || amount == null) {
            log.info("CharLM 未能完整提取实体，使用基于规则的方法进行补充...");
            if (company.isEmpty()) {
                company = extractCompanyFromText(textLines);
            }
            if (date == null) {
                date = extractDateFromText(textLines);
            }
            if (amount == null) {
                amount = extractAmountFromText(textLines);
            }
        }

        log.info("最终提取的实体：Company={}, Date={}, Amount={}", company, date, amount);

        long endTime = System.currentTimeMillis();
        ocrLog.setProcessingTimeMs(endTime - startTime);
        ocrLog.setSuccessful(true);
        ocrProcessingLogRepository.save(ocrLog);

        // 构建响应
        List<String> warnings = new ArrayList<>();
        if (company.isEmpty() || company.equals("Unknown")) {
            warnings.add("Merchant name not detected");
        }
        if (date == null) {
            warnings.add("Date not detected");
        }
        if (amount == null) {
            warnings.add("Total amount not detected");
        }

        double confidence = calculateConfidence(String.join("\n", textLines));

        return OcrResponse.builder()
                .merchant(company.isEmpty() ? "Unknown Merchant" : company)
                .date(date)
                .amount(amount)
                .confidence(confidence)
                .warnings(warnings)
                .requiresManualReview(warnings.size() > 1)
                .build();
    }

    /**
     * 使用传统 Tesseract 方法处理 OCR（后备方法）
     */
    private OcrResponse processWithTraditionalMethod(MultipartFile file, BufferedImage image, 
                                                       OcrProcessingLog ocrLog, long startTime) throws Exception {
        // Convert to grayscale to avoid native Tesseract crashes
        BufferedImage grayImage = convertToGrayscale(image);
        log.info("Converted image to grayscale: {}x{} pixels, type: {}", 
                grayImage.getWidth(), grayImage.getHeight(), grayImage.getType());

        // Create a new Tesseract instance per request
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tesseractDataPath);
        tesseract.setLanguage(tesseractLanguage);
        tesseract.setPageSegMode(3);

        // Validate trained data files
        validateTrainedDataFiles(tesseractDataPath, tesseractLanguage);

        // Perform OCR
        String extractedText = tesseract.doOCR(grayImage);
        log.info("Text extracted by Tesseract:\n{}", extractedText);
        ocrLog.setDetectedText(extractedText);

        // Extract entities using rule-based post-processing
        List<String> textLines = Arrays.asList(extractedText.split("\n"));
        String company = extractCompany(textLines);
        String dateStr = extractDate(textLines);
        String totalStr = extractTotal(textLines);

        LocalDate date = parseDate(dateStr);
        BigDecimal amount = parseAmount(totalStr);

        log.info("Extracted entities: Company={}, Date={}, Amount={}", company, date, amount);

        long endTime = System.currentTimeMillis();
        ocrLog.setProcessingTimeMs(endTime - startTime);
        ocrLog.setSuccessful(true);
        ocrProcessingLogRepository.save(ocrLog);

        List<String> warnings = new ArrayList<>();
        if (company.isEmpty() || company.equals("Unknown")) {
            warnings.add("Merchant name not detected");
        }
        if (date == null) {
            warnings.add("Date not detected");
        }
        if (amount == null) {
            warnings.add("Total amount not detected");
        }

        double confidence = calculateConfidence(extractedText);

        return OcrResponse.builder()
                .merchant(company.isEmpty() ? "Unknown Merchant" : company)
                .date(date)
                .amount(amount)
                .confidence(confidence)
                .warnings(warnings)
                .requiresManualReview(warnings.size() > 1)
                .build();
    }

    // ==================== ONNX Pipeline 方法 ====================

    /**
     * Step 1: Crop Preprocessing - 裁剪预处理（移除多余空白）
     * 参考：split_labels.py 中的 crop_preprocessing 函数
     */
    private BufferedImage cropPreprocessing(BufferedImage originalImage) throws IOException {
        // 转换为 OpenCV Mat
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(originalImage, "png", baos);
        byte[] imageBytes = baos.toByteArray();
        Mat image = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);

        if (image.empty()) {
            throw new IllegalArgumentException("无法解码输入图像");
        }

        try {
            // 1. 转换为灰度图
            Mat gray = new Mat();
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);

            // 2. Otsu 二值化
            Mat threshed = new Mat();
            Imgproc.threshold(gray, threshed, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

            // 3. 形态学操作（腐蚀和膨胀）
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Mat eroded = new Mat();
            Mat dilated = new Mat();
            Imgproc.erode(threshed, eroded, kernel, new Point(-1, -1), 6);
            Imgproc.dilate(eroded, dilated, kernel, new Point(-1, -1), 6);

            // 4. 查找轮廓并获取裁剪区域
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, 
                    Imgproc.CHAIN_APPROX_SIMPLE);

            // 找到最大的轮廓（假设是主要内容区域）
            Rect boundingRect = null;
            double maxArea = 0;
            for (MatOfPoint contour : contours) {
                Rect rect = Imgproc.boundingRect(contour);
                double area = rect.width * rect.height;
                if (area > maxArea) {
                    maxArea = area;
                    boundingRect = rect;
                }
            }

            // 裁剪图像
            Mat cropped;
            if (boundingRect != null && maxArea > image.cols() * image.rows() * 0.1) {
                int x = Math.max(0, boundingRect.x);
                int y = Math.max(0, boundingRect.y);
                int w = Math.min(image.cols() - x, boundingRect.width);
                int h = Math.min(image.rows() - y, boundingRect.height);
                cropped = new Mat(image, new Rect(x, y, w, h));
                log.info("裁剪区域：x={}, y={}, w={}, h={}, area={}", x, y, w, h, maxArea);
            } else {
                cropped = image.clone();
                log.info("未找到有效裁剪区域，使用原图");
            }

            // 转换回 BufferedImage
            MatOfByte outputBuffer = new MatOfByte();
            Imgcodecs.imencode(".png", cropped, outputBuffer);
            ByteArrayInputStream bais = new ByteArrayInputStream(outputBuffer.toArray());
            BufferedImage croppedImage = ImageIO.read(bais);

            // 清理资源
            image.release(); gray.release(); threshed.release();
            eroded.release(); dilated.release(); kernel.release(); hierarchy.release();
            for (MatOfPoint contour : contours) contour.release();
            cropped.release(); outputBuffer.release();

            return croppedImage != null ? croppedImage : originalImage;

        } catch (Exception e) {
            log.error("裁剪预处理失败：{}", e.getMessage(), e);
            image.release();
            return originalImage;
        }
    }

    /**
     * Step 2: CTPN 文本检测
     */
    private List<TextBox> detectTextWithCTPN(BufferedImage image) throws OrtException, IOException {
        if (ctpnSession == null) {
            throw new IllegalStateException("CTPN 模型未加载");
        }

        Mat preprocessedMat = preprocessImageForCTPN(image);

        try {
            float[] imageData = matToFloatArray(preprocessedMat);
            long[] shape = {1, 3, preprocessedMat.rows(), preprocessedMat.cols()};

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(imageData), shape)) {
                Map<String, OnnxTensor> inputs = Collections.singletonMap("input_image", inputTensor);

                long startTime = System.currentTimeMillis();
                try (OrtSession.Result results = ctpnSession.run(inputs)) {
                    log.info("CTPN 推理耗时：{} ms", System.currentTimeMillis() - startTime);

                    // Handle potential 3D array output from ONNX model
                    Object bboxObj = results.get(0).getValue();
                    Object scoreObj = results.get(1).getValue();
                    
                    float[][] bboxPred;
                    float[][] scoreProb;
                    
                    if (bboxObj instanceof float[][][]) {
                        bboxPred = flattenFirstDimension((float[][][]) bboxObj);
                    } else {
                        bboxPred = (float[][]) bboxObj;
                    }
                    
                    if (scoreObj instanceof float[][][]) {
                        scoreProb = flattenFirstDimension((float[][][]) scoreObj);
                    } else {
                        scoreProb = (float[][]) scoreObj;
                    }

                    int imgH = preprocessedMat.rows(), imgW = preprocessedMat.cols();
                    int featH = (int) Math.ceil((double) imgH / FEAT_STRIDE);
                    int featW = (int) Math.ceil((double) imgW / FEAT_STRIDE);

                    float[][] anchors = generateAllAnchorBoxes(featH, featW);
                    float[][] decoded = decodeBboxes(bboxPred, anchors);
                    clipBboxes(decoded, imgW, imgH);

                    List<Integer> validIdx = new ArrayList<>();
                    for (int i = 0; i < scoreProb.length; i++) {
                        if (scoreProb[i][1] > TEXT_PROPOSAL_MIN_SCORE) validIdx.add(i);
                    }

                    float[] validScores = new float[validIdx.size()];
                    float[][] validBboxes = new float[validIdx.size()][];
                    for (int i = 0; i < validIdx.size(); i++) {
                        int idx = validIdx.get(i);
                        validScores[i] = scoreProb[idx][1];
                        validBboxes[i] = decoded[idx];
                    }

                    List<Integer> nmsIdx = nms(validBboxes, validScores, NMS_IOU_THRESHOLD);
                    return connectTextProposals(validBboxes, validScores, nmsIdx, imgW, imgH);
                }
            }
        } finally {
            preprocessedMat.release();
        }
    }

    /**
     * Flatten 3D float array to 2D by merging first two dimensions
     */
    private float[][] flattenFirstDimension(float[][][] array3d) {
        if (array3d.length == 0) {
            return new float[0][0];
        }
        int dim1 = array3d.length;
        int dim2 = array3d[0].length;
        int dim3 = array3d[0][0].length;
        
        float[][] result = new float[dim1 * dim2][dim3];
        for (int i = 0; i < dim1; i++) {
            for (int j = 0; j < dim2; j++) {
                result[i * dim2 + j] = array3d[i][j];
            }
        }
        return result;
    }

    private Mat preprocessImageForCTPN(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        Mat mat = Imgcodecs.imdecode(new MatOfByte(baos.toByteArray()), Imgcodecs.IMREAD_COLOR);
        Mat resized = new Mat();
        Imgproc.resize(mat, resized, new Size(800, 600));
        resized.convertTo(resized, CvType.CV_32F, 1.0 / 255.0);
        mat.release();
        return resized;
    }

    private float[] matToFloatArray(Mat mat) {
        int c = mat.channels(), r = mat.rows(), cols = mat.cols();
        float[] out = new float[c * r * cols], pixels = new float[r * cols * c];
        mat.get(0, 0, pixels);
        for (int ch = 0; ch < c; ch++)
            for (int h = 0; h < r; h++)
                for (int w = 0; w < cols; w++)
                    out[ch * r * cols + h * cols + w] = pixels[h * cols * c + w * c + ch];
        return out;
    }

    private float[][] generateAllAnchorBoxes(int featH, int featW) {
        int n = ANCHOR_HEIGHTS.length;
        float[][] all = new float[featH * featW * n][4];
        float[][] basic = new float[n][4];
        for (int i = 0; i < n; i++) basic[i] = new float[]{0, 0, ANCHOR_SHIFT - 1, ANCHOR_HEIGHTS[i] - 1};
        int idx = 0;
        for (int y = 0; y < featH; y++)
            for (int x = 0; x < featW; x++)
                for (int i = 0; i < n; i++) {
                    all[idx][0] = basic[i][0] + x * FEAT_STRIDE;
                    all[idx][1] = basic[i][1] + y * FEAT_STRIDE;
                    all[idx][2] = basic[i][2] + x * FEAT_STRIDE;
                    all[idx][3] = basic[i][3] + y * FEAT_STRIDE;
                    idx++;
                }
        return all;
    }

    private float[][] decodeBboxes(float[][] pred, float[][] anchors) {
        float[][] dec = new float[pred.length][4];
        for (int i = 0; i < pred.length; i++) {
            float ha = anchors[i][3] - anchors[i][1] + 1;
            float cya = (anchors[i][1] + anchors[i][3]) / 2;
            float vcy = pred[i][0] * ha + cya;
            float vhx = (float) Math.exp(pred[i][1]) * ha;
            dec[i] = new float[]{anchors[i][0], vcy - vhx / 2, anchors[i][2], vcy + vhx / 2};
        }
        return dec;
    }

    private void clipBboxes(float[][] bboxes, int w, int h) {
        for (float[] b : bboxes) {
            b[0] = Math.max(0, Math.min(b[0], w - 1));
            b[1] = Math.max(0, Math.min(b[1], h - 1));
            b[2] = Math.max(0, Math.min(b[2], w - 1));
            b[3] = Math.max(0, Math.min(b[3], h - 1));
        }
    }

    private List<Integer> nms(float[][] bboxes, float[] scores, float thresh) {
        List<Integer> keep = new ArrayList<>();
        Integer[] idx = new Integer[scores.length];
        for (int i = 0; i < scores.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Float.compare(scores[b], scores[a]));
        while (idx.length > 0) {
            keep.add(idx[0]);
            List<Integer> rest = new ArrayList<>();
            for (int j = 1; j < idx.length; j++)
                if (computeIoU(bboxes[idx[0]], bboxes[idx[j]]) <= thresh) rest.add(idx[j]);
            idx = rest.toArray(new Integer[0]);
        }
        return keep;
    }

    private float computeIoU(float[] b1, float[] b2) {
        float x1 = Math.max(b1[0], b2[0]), y1 = Math.max(b1[1], b2[1]);
        float x2 = Math.min(b1[2], b2[2]), y2 = Math.min(b1[3], b2[3]);
        float w = Math.max(0, x2 - x1 + 1), h = Math.max(0, y2 - y1 + 1);
        float inter = w * h;
        float union = (b1[2] - b1[0] + 1) * (b1[3] - b1[1] + 1) + 
                      (b2[2] - b2[0] + 1) * (b2[3] - b2[1] + 1) - inter;
        return union > 0 ? inter / union : 0;
    }

    private List<TextBox> connectTextProposals(float[][] props, float[] scores, 
                                                List<Integer> idx, int imgW, int imgH) {
        List<TextBox> boxes = new ArrayList<>();
        for (int i : idx) {
            TextBox box = new TextBox();
            box.setX1((int) props[i][0]); box.setY1((int) props[i][1]);
            box.setX2((int) props[i][2]); box.setY2((int) props[i][3]);
            box.setConfidence(scores[i]);
            boxes.add(box);
        }
        boxes.sort(Comparator.comparingInt(TextBox::getY1));
        return mergeTextBoxesInSameRow(boxes);
    }

    private List<TextBox> mergeTextBoxesInSameRow(List<TextBox> boxes) {
        if (boxes.isEmpty()) return boxes;
        List<TextBox> merged = new ArrayList<>();
        TextBox cur = boxes.get(0);
        for (int i = 1; i < boxes.size(); i++) {
            TextBox next = boxes.get(i);
            if (Math.abs(cur.getY1() - next.getY1()) < 10) {
                cur.setX2(Math.max(cur.getX2(), next.getX2()));
                cur.setY2(Math.max(cur.getY2(), next.getY2()));
                cur.setConfidence((cur.getConfidence() + next.getConfidence()) / 2);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);
        return merged;
    }

    private List<java.awt.Rectangle> convertTextBoxesToRegions(List<TextBox> boxes) {
        List<java.awt.Rectangle> regions = new ArrayList<>();
        for (TextBox box : boxes)
            regions.add(new java.awt.Rectangle(box.getX1(), box.getY1(), 
                    Math.max(1, box.getX2() - box.getX1()), Math.max(1, box.getY2() - box.getY1())));
        return regions;
    }

    private String runTesseractOnRegions(BufferedImage image, List<java.awt.Rectangle> regions) throws Exception {
        if (regions.isEmpty()) return runFullImageOCR(image);
        StringBuilder sb = new StringBuilder();
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tesseractDataPath);
        tesseract.setLanguage(tesseractLanguage);
        int ok = 0;
        for (java.awt.Rectangle r : regions) {
            try {
                BufferedImage crop = image.getSubimage(
                    Math.max(0, r.x), Math.max(0, r.y),
                    Math.min(r.width, image.getWidth() - r.x),
                    Math.min(r.height, image.getHeight() - r.y));
                String text = tesseract.doOCR(crop);
                if (!text.trim().isEmpty()) { sb.append(text.trim()).append("\n"); ok++; }
            } catch (Exception e) { log.warn("Tesseract 处理区域失败", e); }
        }
        log.info("Tesseract 成功处理 {}/{} 个区域", ok, regions.size());
        return sb.toString().trim();
    }

    private String runFullImageOCR(BufferedImage image) throws Exception {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tesseractDataPath);
        tesseract.setLanguage(tesseractLanguage);
        tesseract.setPageSegMode(3);
        return tesseract.doOCR(image);
    }

    private List<String> postprocessText(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return new ArrayList<>();
        String[] lines = rawText.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String cleaned = line.trim().replaceAll("[\\x00-\\x1f\\x7f-\\x9f]", " ").replaceAll("\\s+", " ");
            if (!cleaned.isEmpty()) out.add(cleaned);
        }
        return out;
    }

    private Map<String, String> extractEntitiesWithCharLM(List<String> textLines) {
        if (charlmSession == null || textLines.isEmpty()) return new HashMap<>();
        try {
            String fullText = String.join(" ", textLines);
            int maxLen = 512;
            int[] indices = new int[maxLen];
            for (int i = 0; i < Math.min(fullText.length(), maxLen); i++) {
                char c = Character.toUpperCase(fullText.charAt(i));
                indices[i] = CHARLM_VOCAB.indexOf(c) >= 0 ? CHARLM_VOCAB.indexOf(c) + 1 : 0;
            }
            LongBuffer buf = LongBuffer.allocate(maxLen);
            for (int idx : indices) buf.put(idx);
            buf.rewind();
            try (OnnxTensor input = OnnxTensor.createTensor(ortEnv, buf, new long[]{1, maxLen})) {
                long start = System.currentTimeMillis();
                try (OrtSession.Result result = charlmSession.run(Collections.singletonMap("input_text", input))) {
                    log.info("CharLM 推理耗时：{} ms", System.currentTimeMillis() - start);
                    float[][][] pred = (float[][][]) result.get(0).getValue();
                    return decodeCharLMPredictions(fullText, pred, textLines);
                }
            }
        } catch (Exception e) {
            log.error("CharLM 提取失败", e);
            return new HashMap<>();
        }
    }

    private Map<String, String> decodeCharLMPredictions(String text, float[][][] pred, List<String> textLines) {
        String[] cats = {"none", "company", "date", "address", "total"};
        Map<String, StringBuilder> builders = new HashMap<>();
        for (int i = 1; i < cats.length; i++) builders.put(cats[i], new StringBuilder());
        for (int i = 0; i < text.length() && i < pred[0].length; i++) {
            int best = 0; float bestProb = -Float.MAX_VALUE;
            for (int c = 0; c < pred[0][i].length; c++)
                if (pred[0][i][c] > bestProb) { bestProb = pred[0][i][c]; best = c; }
            if (best > 0 && best < cats.length) builders.get(cats[best]).append(text.charAt(i));
        }
        Map<String, String> res = new HashMap<>();
        for (Map.Entry<String, StringBuilder> e : builders.entrySet()) {
            String v = e.getValue().toString().trim();
            if (!v.isEmpty()) {
                if ("company".equals(e.getKey())) v = cleanCompany(v);
                else if ("date".equals(e.getKey())) v = extractDateFromLine(v);
                else if ("total".equals(e.getKey())) v = extractTotalFromLine(v);
                res.put(e.getKey(), v);
            }
        }
        return res;
    }

    // ==================== 辅助类 ====================

    public static class TextBox {
        private int x1, y1, x2, y2;
        private double confidence;

        public int getX1() { return x1; }
        public void setX1(int x1) { this.x1 = x1; }
        public int getY1() { return y1; }
        public void setY1(int y1) { this.y1 = y1; }
        public int getX2() { return x2; }
        public void setX2(int x2) { this.x2 = x2; }
        public int getY2() { return y2; }
        public void setY2(int y2) { this.y2 = y2; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }

    // ==================== 实体提取方法 ====================

    /**
     * 从文本行中提取公司名称（基于规则）
     */
    private String extractCompanyFromText(List<String> textLines) {
        return extractCompany(textLines);
    }

    /**
     * 从文本行中提取日期（基于规则）
     */
    private LocalDate extractDateFromText(List<String> textLines) {
        for (String line : textLines) {
            String dateStr = extractDateFromLine(line.trim());
            if (!dateStr.isEmpty()) {
                return parseDate(dateStr);
            }
        }
        return null;
    }

    /**
     * 从文本行中提取金额（基于规则）
     */
    private BigDecimal extractAmountFromText(List<String> textLines) {
        for (String line : textLines) {
            String amountStr = extractTotalFromLine(line.trim());
            if (!amountStr.isEmpty()) {
                return parseAmount(amountStr);
            }
        }
        return null;
    }

    /**
     * Extract company/merchant name from receipt text lines.
     * Ported from Python clean_company and rule-based extraction.
     */
    private String extractCompany(List<String> textLines) {
        if (textLines.isEmpty()) {
            return "";
        }

        // Rule: Company is usually in the first two lines
        // Must not be a number and must not contain unwanted words
        for (int i = 0; i < Math.min(2, textLines.size()); i++) {
            String line = textLines.get(i).trim();
            
            // Check if line matches unwanted pattern
            Matcher unwantedMatcher = UNWANTED_COMPANY_PATTERN.matcher(line);
            if (!unwantedMatcher.matches()) {
                continue;
            }
            
            // Check if it's not just a number
            if (!isNumber(line)) {
                String cleaned = cleanCompany(line);
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        }

        return "";
    }

    /**
     * Clean company name by removing unwanted suffixes.
     */
    private String cleanCompany(String text) {
        // Remove trailing patterns like "(ABC123XYZ)" or "(ABC)"
        Pattern[] patterns = {
            Pattern.compile("(\\d+[^0-9]*[A-Z]+)$"),
            Pattern.compile("\\([A-Z]*\\d+[^0-9]*[A-Z]+\\).*$"),
            Pattern.compile("\\([A-Z\\s]+[\\)]*$")
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text.trim());
            if (matcher.find()) {
                int idx = text.indexOf(matcher.group().trim());
                text = text.substring(0, idx).trim();
                break;
            }
        }

        return text.trim();
    }

    /**
     * Extract date from receipt text lines.
     * Ported from Python extract_date with fallback rules.
     */
    private String extractDate(List<String> textLines) {
        for (String line : textLines) {
            String date = extractDateFromLine(line.trim());
            if (!date.isEmpty()) {
                return date;
            }
        }
        return "";
    }

    /**
     * Extract date from a single line using regex patterns.
     */
    private String extractDateFromLine(String text) {
        Matcher matcher1 = DATE_PATTERN_1.matcher(text);
        if (matcher1.find()) {
            return matcher1.group().trim();
        }

        Matcher matcher2 = DATE_PATTERN_2.matcher(text);
        if (matcher2.find()) {
            return matcher2.group().trim();
        }

        return "";
    }

    /**
     * Extract total amount from receipt text lines.
     * Ported from Python extract_total with fallback rules.
     */
    private String extractTotal(List<String> textLines) {
        String found = "";
        
        // Look for total/amount keywords followed by a number
        for (int i = 0; i < textLines.size(); i++) {
            String line = textLines.get(i).trim();
            
            // Check if line contains total/amount keywords
            boolean hasTotalKeyword = line.toUpperCase().matches(".*\\b(TOTAL|AMOUNT|AMT|DUE)\\b.*");
            boolean hasExcludedKeyword = line.toUpperCase().matches(".*\\b(EX|SUB|CASH|QTY|TAX|INVOICE)\\b.*");
            
            if (hasTotalKeyword && !hasExcludedKeyword) {
                String total = extractTotalFromLine(line);
                if (!total.isEmpty()) {
                    found = total;
                    break;
                }
            }
        }

        // If not found with keywords, search for any valid total pattern
        if (found.isEmpty()) {
            for (String line : textLines) {
                String total = extractTotalFromLine(line.trim());
                if (!total.isEmpty()) {
                    found = total;
                    break;
                }
            }
        }

        return found;
    }

    /**
     * Extract total amount from a single line using regex.
     */
    private String extractTotalFromLine(String text) {
        Matcher matcher = TOTAL_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        return "";
    }

    /**
     * Parse date string to LocalDate object.
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }

        DateTimeFormatter[] formatters = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yy-MM-dd"),
            DateTimeFormatter.ofPattern("yy/MM/dd"),
            DateTimeFormatter.ofPattern("MM-dd-yy"),
            DateTimeFormatter.ofPattern("MM/dd/yy")
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(dateStr, formatter);
            } catch (DateTimeParseException e) {
                // Try next formatter
            }
        }

        log.warn("Could not parse date: {}", dateStr);
        return null;
    }

    /**
     * Parse amount string to BigDecimal object.
     */
    private BigDecimal parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isEmpty()) {
            return null;
        }

        try {
            // Remove currency symbols and commas
            String cleaned = amountStr.replaceAll("[^\\d.]", "");
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            log.warn("Could not parse amount: {}", amountStr);
            return null;
        }
    }

    /**
     * Check if a string is a number.
     */
    private boolean isNumber(String text) {
        try {
            Double.parseDouble(text.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Calculate confidence score based on extracted text quality.
     */
    private double calculateConfidence(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0.0;
        }

        // Simple heuristic: longer text with more lines = better confidence
        int lineCount = text.split("\n").length;
        int charCount = text.length();

        // Base confidence calculation
        double confidence = Math.min(1.0, (lineCount * 0.1 + charCount * 0.001));

        // Reduce confidence if text is very short
        if (charCount < 50) {
            confidence *= 0.5;
        }

        return Math.round(confidence * 100.0) / 100.0;
    }

    /**
     * Validate the uploaded image file.
     */
    private void validateImage(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String contentType = file.getContentType();
        if (!"image/jpeg".equals(contentType) &&
                !"image/png".equals(contentType) &&
                !"image/webp".equals(contentType) &&
                !"image/bmp".equals(contentType) &&
                !"image/gif".equals(contentType)) {
            throw new IllegalArgumentException("Unsupported image format: " + contentType);
        }

        if (file.getSize() > 10 * 1024 * 1024) { // 10MB
            throw new IllegalArgumentException("File too large (max 10MB)");
        }
    }

    /**
     * Validate that required Tesseract trained data files exist.
     * Missing traineddata causes SIGSEGV in native Tesseract code.
     */
    private void validateTrainedDataFiles(String datapath, String languages) {
        String[] langs = languages.split("\\+");
        for (String lang : langs) {
            String langTrimmed = lang.trim();
            java.io.File trainedDataFile = new java.io.File(datapath, langTrimmed + ".traineddata");
            if (!trainedDataFile.exists()) {
                throw new IllegalStateException(
                    "Missing Tesseract traineddata file: " + trainedDataFile.getAbsolutePath() +
                    ". OCR requires language training data files. Ensure '" + langTrimmed + ".traineddata' " +
                    "exists in the configured datapath: '" + datapath + "'.");
            }
        }
        log.info("Validated trained data files exist for languages: {}", languages);
    }

    private BufferedImage convertToGrayscale(BufferedImage originalImage) {
        if (originalImage.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return originalImage; // Already grayscale
        }

        // Create a new grayscale image
        BufferedImage grayImage = new BufferedImage(
                originalImage.getWidth(),
                originalImage.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY);

        // Draw the original image onto the grayscale image, which performs the conversion
        grayImage.getGraphics().drawImage(originalImage, 0, 0, null);
        grayImage.getGraphics().dispose();

        return grayImage;
    }
}
