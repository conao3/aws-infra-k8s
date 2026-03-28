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

    print(f"Starting import-snapshot for {s3_uri}")

    response = ec2.import_snapshot(
        Description=f"NixOS Custom EC2 Snapshot {timestamp}",
        DiskContainer={
            'Format': 'VHD',
            'UserBucket': {
                'S3Bucket': s3_bucket,
                'S3Key': s3_key
            }
        }
    )

    import_task_id = response['ImportTaskId']
    print(f"Import snapshot task started: {import_task_id}")

    while True:
        response = ec2.describe_import_snapshot_tasks(
            ImportTaskIds=[import_task_id]
        )

        task = response['ImportSnapshotTasks'][0]
        status = task['SnapshotTaskDetail']['Status']
        progress = task['SnapshotTaskDetail'].get('Progress', '0')

        print(f"Import status: {status} ({progress}%)")

        if status == 'completed':
            snapshot_id = task['SnapshotTaskDetail']['SnapshotId']
            print(f"Snapshot imported: {snapshot_id}")
            break
        elif status in ['active', 'pending']:
            time.sleep(30)
        else:
            status_message = task['SnapshotTaskDetail'].get('StatusMessage', 'Unknown error')
            raise Exception(f"Snapshot import failed: {status} - {status_message}")

    print(f"Registering AMI from snapshot {snapshot_id}")

    ami_response = ec2.register_image(
        Name=f"nixos-custom-{timestamp}",
        Description=f"NixOS Custom EC2 Image {timestamp}",
        Architecture='arm64',
        RootDeviceName='/dev/xvda',
        VirtualizationType='hvm',
        EnaSupport=True,
        BlockDeviceMappings=[{
            'DeviceName': '/dev/xvda',
            'Ebs': {
                'SnapshotId': snapshot_id,
                'VolumeType': 'gp3',
                'DeleteOnTermination': True
            }
        }]
    )

    ami_id = ami_response['ImageId']
    print(f"AMI registered: {ami_id}")

    ssm.put_parameter(
        Name=f"/{prefix}/custom-ami-id",
        Value=ami_id,
        Type='String',
        Overwrite=True
    )

    return {
        'import_task_id': import_task_id,
        'snapshot_id': snapshot_id,
        'ami_id': ami_id,
        'status': 'completed'
    }
