// LocalMotion QNN Model wrapper - simplified version
#ifndef LM_QNNMODEL_HPP
#define LM_QNNMODEL_HPP

#include <HTP/QnnHtpDevice.h>
#include <inttypes.h>

#include <Config.hpp>
#include <QnnSampleApp.hpp>
#include <QnnTypeMacros.hpp>
#include <cstring>
#include <fstream>
#include <iostream>

using namespace qnn::tools::sample_app;

// Global sample width/height for VAE (set before inference)
extern int g_sample_width;
extern int g_sample_height;

class LmQnnModel : public QnnSampleApp {
 public:
  Qnn_Tensor_t *inputs = nullptr;
  Qnn_Tensor_t *outputs = nullptr;

  LmQnnModel(QnnFunctionPointers qnnFunctionPointers,
             std::string inputListPaths, std::string opPackagePaths,
             void *backendHandle,
             std::string outputPath = "", bool debug = false,
             qnn::tools::iotensor::OutputDataType outputDataType =
                 qnn::tools::iotensor::OutputDataType::FLOAT_ONLY,
             qnn::tools::iotensor::InputDataType inputDataType =
                 qnn::tools::iotensor::InputDataType::FLOAT,
             ProfilingLevel profilingLevel = ProfilingLevel::OFF,
             bool dumpOutputs = false, std::string cachedBinaryPath = "",
             std::string saveBinaryName = "")
      : QnnSampleApp(qnnFunctionPointers, inputListPaths, opPackagePaths,
                     backendHandle, outputPath, debug, outputDataType,
                     inputDataType, profilingLevel, dumpOutputs,
                     cachedBinaryPath, saveBinaryName) {}

  StatusCode enablePerformaceMode() {
    uint32_t powerConfigId;
    uint32_t deviceId = 0;
    uint32_t coreId = 0;
    auto qnnInterface = m_qnnFunctionPointers.qnnInterface;

    QnnDevice_Infrastructure_t deviceInfra = nullptr;
    Qnn_ErrorHandle_t devErr =
        qnnInterface.deviceGetInfrastructure(&deviceInfra);
    if (devErr != QNN_SUCCESS) {
      return StatusCode::FAILURE;
    }
    QnnHtpDevice_Infrastructure_t *htpInfra =
        static_cast<QnnHtpDevice_Infrastructure_t *>(deviceInfra);
    QnnHtpDevice_PerfInfrastructure_t perfInfra = htpInfra->perfInfra;
    Qnn_ErrorHandle_t perfInfraErr =
        perfInfra.createPowerConfigId(deviceId, coreId, &powerConfigId);
    if (perfInfraErr != QNN_SUCCESS) {
      return StatusCode::FAILURE;
    }
    QnnHtpPerfInfrastructure_PowerConfig_t rpcControlLatency;
    memset(&rpcControlLatency, 0, sizeof(rpcControlLatency));
    rpcControlLatency.option =
        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
    rpcControlLatency.rpcControlLatencyConfig = 100;
    const QnnHtpPerfInfrastructure_PowerConfig_t *powerConfigs1[] = {
        &rpcControlLatency, NULL};
    perfInfraErr = perfInfra.setPowerConfig(powerConfigId, powerConfigs1);
    if (perfInfraErr != QNN_SUCCESS) {
      return StatusCode::FAILURE;
    }

    QnnHtpPerfInfrastructure_PowerConfig_t rpcPollingTime;
    memset(&rpcPollingTime, 0, sizeof(rpcPollingTime));
    rpcPollingTime.option =
        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
    rpcPollingTime.rpcPollingTimeConfig = 9999;
    const QnnHtpPerfInfrastructure_PowerConfig_t *powerConfigs2[] = {
        &rpcPollingTime, NULL};
    perfInfraErr = perfInfra.setPowerConfig(powerConfigId, powerConfigs2);
    if (perfInfraErr != QNN_SUCCESS) {
      return StatusCode::FAILURE;
    }

    QnnHtpPerfInfrastructure_PowerConfig_t powerConfig;
    memset(&powerConfig, 0, sizeof(powerConfig));
    powerConfig.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    powerConfig.dcvsV3Config.dcvsEnable = 0;
    powerConfig.dcvsV3Config.setDcvsEnable = 1;
    powerConfig.dcvsV3Config.contextId = powerConfigId;
    powerConfig.dcvsV3Config.powerMode =
        QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    powerConfig.dcvsV3Config.setSleepLatency = 1;
    powerConfig.dcvsV3Config.setBusParams = 1;
    powerConfig.dcvsV3Config.setCoreParams = 1;
    powerConfig.dcvsV3Config.sleepDisable = 1;
    powerConfig.dcvsV3Config.setSleepDisable = 1;
    powerConfig.dcvsV3Config.sleepLatency = 40;
    const QnnHtpPerfInfrastructure_PowerConfig_t *powerConfigs3[] = {
        &powerConfig, NULL};
    perfInfraErr = perfInfra.setPowerConfig(powerConfigId, powerConfigs3);
    if (perfInfraErr != QNN_SUCCESS) {
      return StatusCode::FAILURE;
    }
    return StatusCode::SUCCESS;
  }

  StatusCode executeClipGraphs(int32_t *input_ids, float *text_embedding) {
    size_t graphIdx = 0;

    if (inputs == nullptr || outputs == nullptr) {
      if (qnn::tools::iotensor::StatusCode::SUCCESS !=
          m_ioTensor.setupInputAndOutputTensors(&inputs, &outputs,
                                                (*m_graphsInfo)[graphIdx])) {
        return StatusCode::FAILURE;
      }
    }
    auto graphInfo = (*m_graphsInfo)[graphIdx];
    if (graphInfo.numInputTensors != 1 || graphInfo.numOutputTensors != 1) {
      return StatusCode::FAILURE;
    }

    // input_ids (int32)
    {
      int32_t *input_ids_ptr =
          static_cast<int32_t *>(QNN_TENSOR_GET_CLIENT_BUF(inputs[0]).data);
      memcpy(input_ids_ptr, input_ids, 77 * sizeof(int32_t));
    }

    if (StatusCode::SUCCESS != executeGraphs()) {
      return StatusCode::FAILURE;
    }

    // output: last_hidden_state (float)
    {
      float *output_ptr =
          static_cast<float *>(QNN_TENSOR_GET_CLIENT_BUF(outputs[0]).data);
      int text_embedding_size = 768;  // SD1.5
      memcpy(text_embedding, output_ptr,
             77 * text_embedding_size * sizeof(float));
    }

    return StatusCode::SUCCESS;
  }

  StatusCode executeUnetGraphs(float *latents, int timestep,
                               float *text_embedding, float *latents_pred) {
    size_t graphIdx = 0;

    if (inputs == nullptr || outputs == nullptr) {
      if (qnn::tools::iotensor::StatusCode::SUCCESS !=
          m_ioTensor.setupInputAndOutputTensors(&inputs, &outputs,
                                                (*m_graphsInfo)[graphIdx])) {
        return StatusCode::FAILURE;
      }
    }
    auto graphInfo = (*m_graphsInfo)[graphIdx];
    if (graphInfo.numInputTensors != 3) {
      return StatusCode::FAILURE;
    }

    int single_latent_size = 1 * 4 * g_sample_width * g_sample_height;
    int text_embedding_size = 768;

    // latents (uint16 / float16)
    {
      uint16_t *latents_uint16 =
          static_cast<uint16_t *>(QNN_TENSOR_GET_CLIENT_BUF(inputs[0]).data);
      // Convert float to uint16 (float16) - simple copy, QNN handles format
      const float *src = latents;
      for (int i = 0; i < single_latent_size * 2; i++) {
        latents_uint16[i] = float_to_fp16(src[i]);
      }
    }

    // timestep (int32)
    {
      int32_t *timestep_ptr =
          static_cast<int32_t *>(QNN_TENSOR_GET_CLIENT_BUF(inputs[1]).data);
      *timestep_ptr = timestep;
    }

    // text_embedding (uint16 / float16)
    {
      uint16_t *embed_uint16 =
          static_cast<uint16_t *>(QNN_TENSOR_GET_CLIENT_BUF(inputs[2]).data);
      int embed_count = 77 * text_embedding_size * 2;  // batch_size=2
      const float *src = text_embedding;
      for (int i = 0; i < embed_count; i++) {
        embed_uint16[i] = float_to_fp16(src[i]);
      }
    }

    if (StatusCode::SUCCESS != executeGraphs()) {
      return StatusCode::FAILURE;
    }

    // output: out_sample (uint16 / float16)
    {
      uint16_t *output_uint16 =
          static_cast<uint16_t *>(QNN_TENSOR_GET_CLIENT_BUF(outputs[0]).data);
      int out_count = single_latent_size * 2;  // batch_size=2
      for (int i = 0; i < out_count; i++) {
        latents_pred[i] = fp16_to_float(output_uint16[i]);
      }
    }

    return StatusCode::SUCCESS;
  }

  StatusCode executeVaeDecoderGraphs(float *latents, float *pixel_values) {
    size_t graphIdx = 0;

    if (inputs == nullptr || outputs == nullptr) {
      if (qnn::tools::iotensor::StatusCode::SUCCESS !=
          m_ioTensor.setupInputAndOutputTensors(&inputs, &outputs,
                                                (*m_graphsInfo)[graphIdx])) {
        return StatusCode::FAILURE;
      }
    }
    auto graphInfo = (*m_graphsInfo)[graphIdx];
    if (graphInfo.numInputTensors != 1) {
      return StatusCode::FAILURE;
    }

    int latent_count = 1 * 4 * g_sample_width * g_sample_height;
    int pixel_count = 1 * 3 * g_sample_width * 8 * g_sample_height * 8;

    // latents (uint16 / float16)
    {
      uint16_t *latents_uint16 =
          static_cast<uint16_t *>(QNN_TENSOR_GET_CLIENT_BUF(inputs[0]).data);
      for (int i = 0; i < latent_count; i++) {
        latents_uint16[i] = float_to_fp16(latents[i]);
      }
    }

    if (StatusCode::SUCCESS != executeGraphs()) {
      return StatusCode::FAILURE;
    }

    // output: sample (uint16 / float16)
    {
      uint16_t *output_uint16 =
          static_cast<uint16_t *>(QNN_TENSOR_GET_CLIENT_BUF(outputs[0]).data);
      for (int i = 0; i < pixel_count; i++) {
        pixel_values[i] = fp16_to_float(output_uint16[i]);
      }
    }

    return StatusCode::SUCCESS;
  }

 private:
  // Simple float32 to float16 conversion
  static uint16_t float_to_fp16(float f) {
    uint32_t x = *reinterpret_cast<uint32_t *>(&f);
    uint32_t sign = (x >> 31) & 0x1;
    uint32_t exp = (x >> 23) & 0xFF;
    uint32_t mant = x & 0x7FFFFF;

    int16_t h_exp = static_cast<int16_t>(exp) - 127 + 15;
    if (h_exp <= 0) {
      // Subnormal or zero
      return static_cast<uint16_t>(sign << 15);
    }
    if (h_exp >= 31) {
      // Inf or NaN
      return static_cast<uint16_t>((sign << 15) | 0x7C00 |
                                   (mant ? 0x200 : 0));
    }
    return static_cast<uint16_t>((sign << 15) | (h_exp << 10) |
                                 (mant >> 13));
  }

  static float fp16_to_float(uint16_t h) {
    uint32_t sign = (h >> 15) & 0x1;
    uint32_t exp = (h >> 10) & 0x1F;
    uint32_t mant = h & 0x3FF;

    uint32_t f_exp = exp == 0 ? 0 : (exp + 127 - 15);
    uint32_t f_mant = mant << 13;

    uint32_t f = (sign << 31) | (f_exp << 23) | f_mant;
    return *reinterpret_cast<float *>(&f);
  }
};

#endif  // LM_QNNMODEL_HPP
