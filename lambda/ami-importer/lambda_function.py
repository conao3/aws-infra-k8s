import json
import time
import boto3
import re
import os
from datetime import datetime

ec2 = boto3.client('ec2')
ssm = boto3.client('ssm')

def lambda_handler(event, context):
    prefix = os.environ['PREFIX']
    s3_uri = event['s3_uri']

    s3_bucket = s3_uri.replace('s3://', '').split('/')[0]
    s3_key = '/'.join(s3_uri.replace('s3://', '').split('/')[1:])

    match = re.search(r'nixos-custom-(\d{8}-\d{6})', s3_key)
    timestamp = match.group(1) if match else datetime.now().strftime('%Y%m%d-%H%M%S')

    print(f"Starting import-image for {s3_uri}")

    response = ec2.import_image(
        Description=f"NixOS Custom EC2 Image {timestamp}",
        Architecture='arm64',
        Platform='Linux',
        DiskContainers=[{
            'Format': 'VHD',
            'UserBucket': {
                'S3Bucket': s3_bucket,
                'S3Key': s3_key
            }
        }]
    )

    import_task_id = response['ImportTaskId']
    print(f"Import task started: {import_task_id}")

    ssm.put_parameter(
        Name=f"/{prefix}/ami-builder/import-task-id",
        Value=import_task_id,
        Type='String',
        Overwrite=True
    )

    while True:
        response = ec2.describe_import_image_tasks(
            ImportTaskIds=[import_task_id]
        )

        task = response['ImportImageTasks'][0]
        status = task['Status']

        print(f"Import status: {status}")

        if status == 'completed':
            ami_id = task['ImageId']
            print(f"Import completed: {ami_id}")

            ssm.put_parameter(
                Name=f"/{prefix}/custom-ami-id",
                Value=ami_id,
                Type='String',
                Overwrite=True
            )

            return {
                'import_task_id': import_task_id,
                'ami_id': ami_id,
                'status': 'completed'
            }
        elif status in ['active', 'pending']:
            time.sleep(60)
        else:
            status_message = task.get('StatusMessage', 'Unknown error')
            print(f"Import failed: {status} - {status_message}")
            raise Exception(f"{status} - {status_message}")
