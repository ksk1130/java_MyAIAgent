        *> call_symfo_inst.cbl
        *> symfo_inst.cblを呼び出すプログラム
        *> インサート用のデータを準備して渡す
        
          IDENTIFICATION DIVISION.
          PROGRAM-ID.   call_symfo_inst.
          
          ENVIRONMENT DIVISION.
          CONFIGURATION SECTION.
          SPECIAL-NAMES.
            CONSOLE IS CONS.
          INPUT-OUTPUT SECTION.
          FILE-CONTROL.
          
          DATA DIVISION.
          FILE SECTION.
          
          WORKING-STORAGE SECTION.
        * ホスト変数の定義
              COPY "HOST_VARS.cpy".
        
        * ローカル作業変数
          01  WS-RETURN-CODE         PIC 9(4) VALUE 0.
          01  WS-RECORD-COUNT        PIC 9(9) VALUE 0.
          01  WS-ERROR-MSG           PIC X(100) VALUE SPACES.
          01  WS-INPUT-DATA.
              05  WS-INPUT-ZIPCODE   PIC X(7).
              05  WS-INPUT-ADDRESS   PIC N(50).
          01  WS-STATUS              PIC X(10) VALUE SPACES.
          
          PROCEDURE DIVISION.
          MAIN-PROCEDURE SECTION.
        * データ準備開始
              DISPLAY "===== symfo_instプログラム呼び出し開始 =====".
              DISPLAY "インサート用データを準備中...".
              
        * サンプルデータの準備
              PERFORM PREPARE-INSERT-DATA.
              
        * symfo_instプログラムの呼び出し
              DISPLAY "symfo_instプログラムを呼び出します".
              CALL "symfo_inst" 
                  USING HOST-VARS
                  RETURNING WS-RETURN-CODE
              END-CALL.
              
        * 実行結果の確認
              EVALUATE TRUE
                  WHEN WS-RETURN-CODE = 0
                      DISPLAY "✓ symfo_instの実行に成功しました"
                  WHEN OTHER
                      DISPLAY "✗ エラー: リターンコード = " 
                          WS-RETURN-CODE
              END-EVALUATE.
              
        * SQLSTATEの確認
              IF SQLSTATE NOT = "00000"
                  DISPLAY "SQLエラー: " SQLSTATE
                  DISPLAY "SQLメッセージ: " SQLMSG
                  MOVE 1 TO WS-RETURN-CODE
              END-IF.
              
              DISPLAY "===== symfo_instプログラム呼び出し終了 =====".
              
              MOVE WS-RETURN-CODE TO PROGRAM-STATUS.
              EXIT PROGRAM.
          
          PREPARE-INSERT-DATA SECTION.
        * インサート用データの準備
        * ここでZIPCODEとADDR-CITYに値を設定する
              
              DISPLAY "郵便番号の入力: ".
              ACCEPT WS-INPUT-ZIPCODE.
              MOVE WS-INPUT-ZIPCODE TO ZIPCODE.
              
              DISPLAY "住所の入力: ".
              ACCEPT WS-INPUT-ADDRESS.
              MOVE WS-INPUT-ADDRESS TO ADDR-CITY.
              
              DISPLAY "入力データ".
              DISPLAY "  郵便番号: " ZIPCODE.
              DISPLAY "  住所: " ADDR-CITY.
              DISPLAY " ".
              
          PREPARE-INSERT-DATA-END.
              EXIT.
          
          END PROGRAM call_symfo_inst.
