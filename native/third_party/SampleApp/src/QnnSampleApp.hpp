//==============================================================================
//
//  Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
//  All rights reserved.
//  Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================
#pragma once

#include <list>
#include <memory>
#include <queue>

#include "IOTensor.hpp"
#include "SampleApp.hpp"

// Forward declaration for friend class in QnnSampleApp
class LmQnnModel;

namespace qnn {
namespace tools {
namespace sample_app {

enum class StatusCode {
  SUCCESS,
  FAILURE,
  FAILURE_INPUT_LIST_EXHAUSTED,
  FAILURE_SYSTEM_ERROR,
  FAILURE_SYSTEM_COMMUNICATION_ERROR,
  QNN_FEATURE_UNSUPPORTED
};

class QnnSampleApp {
  friend class ::LmQnnModel;  // Allow access to private members

 public:
  QnnSampleApp(QnnFunctionPointers qnnFunctionPointers,
               std::string inputListPaths,
               std::string opPackagePaths,
               void *backendHandle,
               std::string outputPath                  = s_defaultOutputPath,
               bool debug                              = false,
               iotensor::OutputDataType outputDataType = iotensor::OutputDataType::FLOAT_ONLY,
               iotensor::InputDataType inputDataType   = iotensor::InputDataType::FLOAT,
               ProfilingLevel profilingLevel           = ProfilingLevel::OFF,
               bool dumpOutputs                        = false,
               std::string cachedBinaryPath            = "",
               std::string saveBinaryName              = "",
               unsigned int numInferences              = 1,
               bool serializeProfileLogs               = false,
               std::string dlcPath                     = "");

  ~QnnSampleApp();

  // @brief Print a message to STDERR then return a nonzero
  //  exit status.
  int32_t reportError(const std::string &err);

  StatusCode initialize();

  StatusCode initializeBackend();

  StatusCode createContext();

  StatusCode composeGraphs();

  StatusCode finalizeGraphs();

  StatusCode executeGraphs();

  StatusCode registerOpPackages();

  StatusCode createFromBinary();

  StatusCode saveBinary();

  StatusCode freeContext();

  StatusCode terminateBackend();

  StatusCode initializeProfiling();

  std::string getBackendBuildId();

  StatusCode isDevicePropertySupported();

  StatusCode isFinalizeDeserializedGraphSupported();

  StatusCode createDevice();

  StatusCode freeDevice();

  StatusCode verifyFailReturnStatus(Qnn_ErrorHandle_t errCode);

 private:
  StatusCode extractBackendProfilingInfo(Qnn_ProfileHandle_t profileHandle,
                                         QnnSystemProfile_ProfileData_t *profileData);

  StatusCode extractProfilingSubEvents(
      QnnProfile_EventId_t profileEventId,
      QnnSystemProfile_ProfileEventV1_t &profileEvent,
      std::list<std::vector<QnnSystemProfile_ProfileEventV1_t>> &profilingSubEvents);

  StatusCode extractProfilingEvent(QnnProfile_EventId_t profileEventId,
                                   QnnSystemProfile_ProfileEventV1_t &profileEvent);

  StatusCode composeGraphsFromDlc();

  static const std::string s_defaultOutputPath;

  QnnFunctionPointers m_qnnFunctionPointers;
  std::vector<std::string> m_inputListPaths;
  std::vector<std::vector<std::vector<std::string>>> m_inputFileLists;
  std::vector<std::unordered_map<std::string, uint32_t>> m_inputNameToIndex;
  std::vector<std::string> m_opPackagePaths;
  std::string m_outputPath;
  std::string m_saveBinaryName;
  std::string m_cachedBinaryPath;
  QnnBackend_Config_t **m_backendConfig = nullptr;
  Qnn_ContextHandle_t m_context         = nullptr;
  QnnContext_Config_t **m_contextConfig = nullptr;
  bool m_debug;
  iotensor::OutputDataType m_outputDataType;
  iotensor::InputDataType m_inputDataType;
  ProfilingLevel m_profilingLevel;
  bool m_serializeProfileLogs = false;
  bool m_dumpOutputs;
  qnn_wrapper_api::GraphInfo_t **m_graphsInfo = nullptr;
  uint32_t m_graphsCount;
  iotensor::IOTensor m_ioTensor;
  bool m_isBackendInitialized;
  bool m_isContextCreated;
  Qnn_ProfileHandle_t m_profileBackendHandle              = nullptr;
  qnn_wrapper_api::GraphConfigInfo_t **m_graphConfigsInfo = nullptr;
  uint32_t m_graphConfigsInfoCount;
  Qnn_LogHandle_t m_logHandle         = nullptr;
  Qnn_BackendHandle_t m_backendHandle = nullptr;
  Qnn_DeviceHandle_t m_deviceHandle   = nullptr;
  unsigned int m_numInferences;
  QnnSystemProfile_SerializationTargetHandle_t m_serializationTargetHandle = nullptr;

  std::string m_dlcPath;
  QnnSystemDlc_Handle_t m_dlcHandle = nullptr;
  Qnn_LogHandle_t m_dlcLogHandle = nullptr;
};
}  // namespace sample_app
}  // namespace tools
}  // namespace qnn
