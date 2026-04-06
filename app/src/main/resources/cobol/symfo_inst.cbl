       *>symfo_inst
       * https://symfoware.blog.fc2.com/blog-entry-31.html
        IDENTIFICATION DIVISION.
        PROGRAM-ID.   symfo_inst.
        ENVIRONMENT    DIVISION.
        CONFIGURATION  SECTION.
       *画面からの入力受け付け宣言
          SPECIAL-NAMES.
            CONSOLE IS CONS.
        INPUT-OUTPUT   SECTION.
        FILE-CONTROL.
       *読み込むフィルの指定
       *perlで編集した郵便番号-住所ファイルを読み込む
            SELECT  F1  ASSIGN  TO  "out.csv"  STATUS  FST.
        DATA DIVISION.
        FILE SECTION.
       *ファイルのレコード定義
        FD  F1.
          01 F1R.
            02  F1PAD1                    PIC X(1).
            02  F1ZIPCODE                 PIC X(7).
            02  F1PAD2                    PIC X(3).
            02  F1ADDRESS                 PIC N(50).
            02  F1PAD3                    PIC X(3).
        WORKING-STORAGE SECTION.
       *ファイルのステータス変数
         01  FST                    PIC X(02).
       *COPY句を使用してホスト変数を読み込む
            COPY "HOST_VARS.cpy".
       *
        01  COUNT1       PIC 9(1)  BINARY.
        01  WORK         PIC X(12).
       *
        PROCEDURE DIVISION.
        MAIN SECTION.
       *COPY句を使用してテーブル定義を読み込む
            COPY "POST_CD.cpy".
       
       *    SAMPLEデータベースへ接続
            EXEC SQL CONNECT TO 'SAMPLE' END-EXEC.
       
       *ファイルをオープンし、データを取得する
            OPEN  INPUT  F1
            PERFORM  UNTIL  FST  NOT  =  "00"
                READ  F1
                    END
                        CONTINUE
                    NOT END
       *INSERT用の変数に待避
                        MOVE F1ZIPCODE TO ZIPCODE
                        MOVE F1ADDRESS TO ADDRESS_NAME
       *INSERT文実行
                        PERFORM INSERT-DATA
                END-READ
            END-PERFORM.
            CLOSE  F1.
       
       *COMMITを実行し、データを確定させる
            EXEC SQL COMMIT WORK END-EXEC.
       *SAMPLEデータベースとの接続を切る
            EXEC SQL DISCONNECT 'SAMPLE' END-EXEC.
            MOVE  0  TO  PROGRAM-STATUS.
            EXIT PROGRAM.
       
        INSERT-DATA SECTION.
       *INSERT文を実行する
       *:ZIPCODE,ADDRESS_NAMEはSQL文を実行する際、変数に置き換えられる
            EXEC SQL
              INSERT INTO POST_CD (郵便番号,住所) VALUES (:ZIPCODE,:ADDRESS_NAME)
            END-EXEC.
        INSERT-DATA-END.
            EXIT.
       
        END PROGRAM symfo_inst.
       